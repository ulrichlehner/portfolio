package name.abuchen.portfolio.snapshot.lookthrough;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Optional;
import java.util.TreeMap;

/** Bounded model-only format. No Java object deserialization or raw responses. */
final class CompositionCacheCodec
{
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int SCHEMA_VERSION = 1;
    private static final int VALIDATION_VERSION = 1;
    private static final int MAX_ROWS = 10000;
    private static final int MAX_TEXT_BYTES = 4096;

    private CompositionCacheCodec()
    {
    }

    static Optional<CompositionValidationResult> validate(FundComposition source)
    {
        try
        {
            if (source.constituents().isEmpty())
                return Optional.empty();
            // Apply the same serialization/resource bounds in BOTH modes.
            encode(source);
            for (var row : source.constituents())
            {
                var text = row.weight().orElse(""); //$NON-NLS-1$
                if (text.length() > 128)
                    return Optional.empty();
                var decimal = new BigDecimal(text);
                if (decimal.precision() > 128 || Math.abs((long) decimal.scale()) > 10000)
                    return Optional.empty();
            }
            var result = new CompositionValidator().validate(source);
            return result.isUsable() ? Optional.of(result) : Optional.empty();
        }
        catch (IOException | IllegalArgumentException e)
        {
            return Optional.empty();
        }
    }

    static byte[] digest(byte[] bytes)
    {
        try
        {
            return MessageDigest.getInstance("SHA-256").digest(bytes); //$NON-NLS-1$
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }

    static byte[] encode(FundComposition source) throws IOException
    {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes))
        {
            out.writeInt(SCHEMA_VERSION);
            out.writeInt(VALIDATION_VERSION);
            text(out, source.provenance().source().providerId());
            text(out, source.provenance().source().accountId());
            instrument(out, source.provenance().fund());
            text(out, source.provenance().retrievedAt().toString());
            text(out, source.provenance().providerRefreshedAt().map(Instant::toString).orElse("")); //$NON-NLS-1$
            text(out, source.provenance().holdingsEffectiveDate().map(LocalDate::toString).orElse("")); //$NON-NLS-1$
            text(out, source.denominator().name());
            text(out, source.exposureBasis().name());
            text(out, source.completeness().name());
            count(source.constituents().size(), MAX_ROWS);
            out.writeInt(source.constituents().size());
            for (var row : source.constituents())
            {
                instrument(out, row.instrument());
                out.writeBoolean(row.weight().isPresent());
                if (row.weight().isPresent())
                    text(out, row.weight().orElseThrow());
                if (bytes.size() > MAX_BYTES - 32)
                    throw new IOException();
            }
        }
        var body = bytes.toByteArray();
        bytes.write(digest(body));
        if (bytes.size() > MAX_BYTES)
            throw new IOException();
        return bytes.toByteArray();
    }

    static FundComposition decode(byte[] bytes) throws IOException
    {
        if (bytes.length < 40 || bytes.length > MAX_BYTES)
            throw new IOException();
        var body = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(digest(body), Arrays.copyOfRange(bytes, body.length, bytes.length)))
            throw new IOException();
        try (var in = new DataInputStream(new ByteArrayInputStream(body)))
        {
            if (in.readInt() != SCHEMA_VERSION || in.readInt() != VALIDATION_VERSION)
                throw new IOException();
            var account = new ProviderAccount(text(in), text(in));
            var fund = instrument(in);
            var retrieved = Instant.parse(text(in));
            var refreshed = text(in);
            var effective = text(in);
            var provenance = new CompositionProvenance(account, fund, retrieved,
                            refreshed.isEmpty() ? Optional.empty() : Optional.of(Instant.parse(refreshed)),
                            effective.isEmpty() ? Optional.empty() : Optional.of(LocalDate.parse(effective)));
            var denominator = FundComposition.Denominator.valueOf(text(in));
            var basis = FundComposition.ExposureBasis.valueOf(text(in));
            var completeness = FundComposition.Completeness.valueOf(text(in));
            int size = count(in.readInt(), MAX_ROWS);
            var rows = new ArrayList<FundComposition.Constituent>();
            for (int index = 0; index < size; index++)
                rows.add(new FundComposition.Constituent(instrument(in),
                                in.readBoolean() ? Optional.of(text(in)) : Optional.empty()));
            if (in.available() != 0)
                throw new IOException();
            return new FundComposition(provenance, denominator, basis, completeness, rows);
        }
        catch (RuntimeException e)
        {
            throw new IOException();
        }
    }

    private static void instrument(DataOutputStream out, CompositionInstrument instrument) throws IOException
    {
        text(out, instrument.label());
        count(instrument.identifiers().size(), 32);
        out.writeInt(instrument.identifiers().size());
        for (var identifier : new TreeMap<>(instrument.identifiers()).entrySet())
        {
            text(out, identifier.getKey());
            text(out, identifier.getValue());
        }
    }

    private static CompositionInstrument instrument(DataInputStream in) throws IOException
    {
        var label = text(in);
        int size = count(in.readInt(), 32);
        var identifiers = new HashMap<String, String>();
        for (int index = 0; index < size; index++)
        {
            if (identifiers.put(text(in), text(in)) != null)
                throw new IOException();
        }
        return new CompositionInstrument(label, identifiers);
    }

    private static int count(int value, int maximum) throws IOException
    {
        if (value < 0 || value > maximum)
            throw new IOException();
        return value;
    }

    private static void text(DataOutputStream out, String value) throws IOException
    {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        count(bytes.length, MAX_TEXT_BYTES);
        if (!value.equals(new String(bytes, StandardCharsets.UTF_8)))
            throw new IOException();
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String text(DataInputStream in) throws IOException
    {
        int size = count(in.readInt(), MAX_TEXT_BYTES);
        var bytes = in.readNBytes(size);
        if (bytes.length != size)
            throw new IOException();
        var text = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, text.getBytes(StandardCharsets.UTF_8)))
            throw new IOException();
        return text;
    }
}
