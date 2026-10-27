package name.abuchen.portfolio.ui.preferences;

import java.util.Objects;
import java.util.Optional;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Text;

import name.abuchen.portfolio.online.CompositionCredentials;
import name.abuchen.portfolio.online.impl.EulerpoolFundCompositionProvider;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePreferences;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.util.DesktopAPI;
import name.abuchen.portfolio.ui.views.lookthrough.DevelopmentCredentials;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughCompositionService;

/** No field editor: keys must never enter the dialog's ordinary preference store. */
public class EulerpoolPreferencePage extends PreferencePage
{
    public static final ProviderAccount ACCOUNT = new ProviderAccount(EulerpoolFundCompositionProvider.ID, "default"); //$NON-NLS-1$

    private final CompositionCredentials credentials;
    private final CompositionPersistencePreferences persistencePreferences;
    private final Runnable persistenceApplied;
    private Text key;
    private Button remove;
    private Button sessionOnly;
    private Label status;
    private Button confirmation;
    private Button persistence;
    private Label persistenceStatus;
    private Button registerLookup;

    public EulerpoolPreferencePage()
    {
        // Installing here as well keeps the development key visible when the
        // page is opened before the Breakdown view ever initialised its backend.
        this(DevelopmentCredentials.install(CompositionCredentials.local(), ACCOUNT));
    }

    public EulerpoolPreferencePage(CompositionCredentials credentials)
    {
        this(credentials, new EclipseCompositionPersistencePreferences());
    }

    public EulerpoolPreferencePage(CompositionCredentials credentials,
                    CompositionPersistencePreferences persistencePreferences)
    {
        this(credentials, persistencePreferences, LookThroughCompositionService::reconfigure);
    }

    /**
     * @param persistenceApplied
     *            called after the storage settings changed, so the running
     *            session applies them and removes withdrawn data at once
     */
    public EulerpoolPreferencePage(CompositionCredentials credentials,
                    CompositionPersistencePreferences persistencePreferences, Runnable persistenceApplied)
    {
        this.credentials = Objects.requireNonNull(credentials);
        this.persistencePreferences = Objects.requireNonNull(persistencePreferences);
        this.persistenceApplied = Objects.requireNonNull(persistenceApplied);
        setTitle(Messages.PrefTitleEulerpool);
        noDefaultButton();
    }

    /**
     * The user's confirmation is the only permission available here: it makes
     * local storage permitted without a maximum age, otherwise it stays
     * unverified. This is not a verified provider permission.
     */
    static CompositionPersistencePolicy policyFor(boolean localStorageConfirmed)
    {
        return new CompositionPersistencePolicy(ACCOUNT,
                        localStorageConfirmed ? CompositionPersistencePolicy.Permission.PERMITTED
                                        : CompositionPersistencePolicy.Permission.UNVERIFIED,
                        Optional.empty());
    }

    @Override
    protected Control createContents(Composite parent)
    {
        var area = new Composite(parent, SWT.NONE);
        GridLayoutFactory.swtDefaults().numColumns(3).applyTo(area);

        var description = new Label(area, SWT.WRAP);
        description.setText(Messages.PrefEulerpoolDescription);
        GridDataFactory.fillDefaults().span(3, 1).grab(true, false).hint(450, SWT.DEFAULT).applyTo(description);

        var apiKeyLink = new Link(area, SWT.NONE);
        apiKeyLink.setText("<a>" + Messages.PrefEulerpoolAPIKeyLink + "</a>"); //$NON-NLS-1$
        apiKeyLink.addSelectionListener(SelectionListener.widgetSelectedAdapter(
                        event -> DesktopAPI.browse("https://eulerpool.com/developers/register"))); //$NON-NLS-1$
        GridDataFactory.fillDefaults().span(3, 1).grab(true, false).applyTo(apiKeyLink);

        var label = new Label(area, SWT.NONE);
        label.setText(Messages.PrefTitleAPIKey);
        key = new Text(area, SWT.BORDER | SWT.PASSWORD | SWT.SINGLE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(key);
        key.setTextLimit(8192);
        // Never prefill a stored secret, including when reopening the page.
        key.addDisposeListener(event -> key.setText("")); //$NON-NLS-1$

        remove = new Button(area, SWT.PUSH);
        remove.setText(Messages.PrefEulerpoolRemove);
        remove.addSelectionListener(SelectionListener.widgetSelectedAdapter(event -> remove()));

        // The dialog header clips a wrapped message to one visible line, so
        // the status is shown here where the full text fits.
        status = new Label(area, SWT.WRAP);
        GridDataFactory.fillDefaults().span(3, 1).grab(true, false).hint(450, SWT.DEFAULT).applyTo(status);

        // Only offered after secure storage refused the key.
        sessionOnly = new Button(area, SWT.CHECK);
        sessionOnly.setText(Messages.PrefEulerpoolSessionOnly);
        GridDataFactory.fillDefaults().span(3, 1).applyTo(sessionOnly);
        setSessionOnlyVisible(false);

        updateStatus();

        var separator = new Label(area, SWT.SEPARATOR | SWT.HORIZONTAL);
        GridDataFactory.fillDefaults().span(3, 1).grab(true, false).indent(0, 10).applyTo(separator);

        confirmation = new Button(area, SWT.CHECK);
        confirmation.setText(Messages.PrefEulerpoolLocalStorageConfirm);
        confirmation.setSelection(persistencePreferences.isLocalStorageConfirmed(ACCOUNT));
        // Stored with OK or Apply, so Cancel leaves the confirmation unchanged.
        confirmation.addSelectionListener(SelectionListener.widgetSelectedAdapter(event -> {
            persistence.setEnabled(confirmation.getSelection());
            updatePersistenceStatus();
        }));
        GridDataFactory.fillDefaults().span(3, 1).applyTo(confirmation);

        persistence = new Button(area, SWT.CHECK);
        persistence.setText(Messages.PrefEulerpoolPersistenceRetain);
        persistence.setSelection(persistencePreferences.get(ACCOUNT).orElse(true));
        persistence.setEnabled(confirmation.getSelection());
        persistence.addSelectionListener(
                        SelectionListener.widgetSelectedAdapter(event -> updatePersistenceStatus()));
        GridDataFactory.fillDefaults().span(3, 1).applyTo(persistence);

        persistenceStatus = new Label(area, SWT.WRAP);
        GridDataFactory.fillDefaults().span(3, 1).grab(true, false).hint(450, SWT.DEFAULT).applyTo(persistenceStatus);

        updatePersistenceStatus();

        var registerSeparator = new Label(area, SWT.SEPARATOR | SWT.HORIZONTAL);
        GridDataFactory.fillDefaults().span(3, 1).grab(true, false).indent(0, 10).applyTo(registerSeparator);

        // Stored with OK or Apply like the storage settings.
        registerLookup = new Button(area, SWT.CHECK);
        registerLookup.setText(Messages.PrefEulerpoolRegisterLookup);
        registerLookup.setSelection(persistencePreferences.isRegisterLookupEnabled());
        GridDataFactory.fillDefaults().span(3, 1).applyTo(registerLookup);
        return area;
    }

    /** Outcome of submitting the key field; independent of any widget. */
    enum Outcome
    {
        NOTHING, STORED, SESSION_ONLY, INVALID_KEY, STORAGE_UNAVAILABLE, DELETION_FAILED
    }

    /**
     * Stores the entered key. An empty field leaves the stored key untouched;
     * an invalid key is rejected without touching any stored key.
     */
    Outcome submit(String input, boolean sessionOnly)
    {
        if (input == null || input.isEmpty())
            return Outcome.NOTHING;

        var result = sessionOnly ? credentials.replaceSession(ACCOUNT, input)
                        : credentials.replaceSecure(ACCOUNT, input);
        return switch (result)
        {
            case STORED -> Outcome.STORED;
            case SESSION_ONLY -> Outcome.SESSION_ONLY;
            case INVALID_KEY -> Outcome.INVALID_KEY;
            case STORAGE_UNAVAILABLE -> Outcome.STORAGE_UNAVAILABLE;
            case DELETION_FAILED, REMOVED -> Outcome.DELETION_FAILED;
        };
    }

    /** Apply delegates to this method (PreferencePage#performApply). */
    @Override
    public boolean performOk()
    {
        // Never realized pages (the dialog saves every page) have no field.
        if (key == null || key.isDisposed())
            return true;

        applyPersistence(confirmation.getSelection(), persistence.getSelection());
        applyRegisterLookup(registerLookup.getSelection());

        var outcome = submit(key.getText(), sessionOnly.getSelection());
        switch (outcome)
        {
            case INVALID_KEY:
                setErrorMessage(Messages.PrefEulerpoolInvalidKey);
                return false;
            case STORAGE_UNAVAILABLE:
                setSessionOnlyVisible(true);
                setErrorMessage(Messages.PrefEulerpoolSessionHint);
                updateStatus();
                return false;
            case DELETION_FAILED:
                setErrorMessage(Messages.PrefEulerpoolDeletionFailed);
                updateStatus();
                return false;
            default:
                setErrorMessage(null);
                key.setText(""); //$NON-NLS-1$
                setSessionOnlyVisible(false);
                updateStatus();
                return true;
        }
    }

    /**
     * Stores the storage settings. Clearing the confirmation withdraws local
     * storage: the running session then deletes the account's stored data.
     */
    void applyPersistence(boolean confirmed, boolean retain)
    {
        // The retention preference only applies while storage is confirmed.
        if (confirmed)
            persistencePreferences.set(ACCOUNT, retain);
        if (persistencePreferences.isLocalStorageConfirmed(ACCOUNT) != confirmed)
            persistencePreferences.setLocalStorageConfirmed(ACCOUNT, confirmed);
        persistenceApplied.run();
    }

    /**
     * Stores the register switch. Switched off, no register request is made
     * and countries come from profiles or ISIN prefixes; the openly licensed
     * register data already stored is kept.
     */
    void applyRegisterLookup(boolean enabled)
    {
        if (persistencePreferences.isRegisterLookupEnabled() != enabled)
            persistencePreferences.setRegisterLookupEnabled(enabled);
    }

    private void remove()
    {
        var result = credentials.remove(ACCOUNT);
        setErrorMessage(result == CompositionCredentials.Result.DELETION_FAILED ? Messages.PrefEulerpoolDeletionFailed
                        : null);
        key.setText(""); //$NON-NLS-1$
        setSessionOnlyVisible(false);
        updateStatus();
    }

    private void setSessionOnlyVisible(boolean visible)
    {
        if (!visible)
            sessionOnly.setSelection(false);
        sessionOnly.setVisible(visible);
        ((GridData) sessionOnly.getLayoutData()).exclude = !visible;
        sessionOnly.getParent().layout(true, true);
    }

    private void updatePersistenceStatus()
    {
        var effective = policyFor(confirmation.getSelection())
                        .effectiveModeDetail(Optional.of(persistence.getSelection()));
        var reason = switch (effective.reason())
        {
            case PERMITTED -> Messages.PrefEulerpoolPersistenceModePersistent;
            case USER_OPT_OUT -> Messages.PrefEulerpoolPersistenceReasonUserOptOut;
            case POLICY_PROHIBITED -> Messages.PrefEulerpoolPersistenceReasonPolicyProhibited;
            case POLICY_UNVERIFIED -> Messages.PrefEulerpoolPersistenceReasonPolicyUnverified;
        };
        persistenceStatus.setText(reason);
        persistenceStatus.getParent().layout(true, true);
    }

    private void updateStatus()
    {
        var mode = credentials.state(ACCOUNT).mode();
        var state = switch (mode)
        {
            case UNCONFIGURED -> Messages.PrefEulerpoolUnconfigured;
            case SECURE -> Messages.PrefEulerpoolSecure;
            case SESSION_ONLY -> Messages.PrefEulerpoolSession;
            case UNAVAILABLE -> Messages.PrefEulerpoolUnavailable;
        };
        var hasKey = mode == CompositionCredentials.Mode.SECURE || mode == CompositionCredentials.Mode.SESSION_ONLY;
        // The hint only matters when a key exists; an empty field must not look
        // like a lost key.
        key.setMessage(hasKey ? "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" : ""); //$NON-NLS-1$ //$NON-NLS-2$
        remove.setEnabled(hasKey);
        // Without secure storage the session-only entry is offered right away.
        if (mode == CompositionCredentials.Mode.UNAVAILABLE)
            setSessionOnlyVisible(true);
        status.setText(state);
        status.getParent().layout(true, true);
    }
}
