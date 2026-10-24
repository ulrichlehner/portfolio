package name.abuchen.portfolio.ui.preferences;

import java.util.Objects;
import java.util.Optional;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import name.abuchen.portfolio.online.CompositionCredentials;
import name.abuchen.portfolio.online.CompositionCredentials.Result;
import name.abuchen.portfolio.online.impl.EulerpoolFundCompositionProvider;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePreferences;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;
import name.abuchen.portfolio.ui.Messages;

/** No field editor: keys must never enter the dialog's ordinary preference store. */
public class EulerpoolPreferencePage extends PreferencePage
{
    public static final ProviderAccount ACCOUNT = new ProviderAccount(EulerpoolFundCompositionProvider.ID, "default"); //$NON-NLS-1$

    private final CompositionCredentials credentials;
    private final CompositionPersistencePreferences persistencePreferences;
    private final CompositionPersistencePolicy persistencePolicy;
    private Text key;
    private Label status;
    private Button persistence;
    private Label persistenceStatus;

    public EulerpoolPreferencePage()
    {
        this(CompositionCredentials.local());
    }

    public EulerpoolPreferencePage(CompositionCredentials credentials)
    {
        this(credentials, new EclipseCompositionPersistencePreferences());
    }

    public EulerpoolPreferencePage(CompositionCredentials credentials,
                    CompositionPersistencePreferences persistencePreferences)
    {
        this(credentials, persistencePreferences, unverifiedPolicy());
    }

    public EulerpoolPreferencePage(CompositionCredentials credentials,
                    CompositionPersistencePreferences persistencePreferences,
                    CompositionPersistencePolicy persistencePolicy)
    {
        this.credentials = Objects.requireNonNull(credentials);
        this.persistencePreferences = Objects.requireNonNull(persistencePreferences);
        this.persistencePolicy = Objects.requireNonNull(persistencePolicy);
        setTitle(Messages.PrefTitleEulerpool);
        noDefaultAndApplyButton();
    }

    /** Retained storage is unavailable until task 6.3 verifies permission and retention. */
    private static CompositionPersistencePolicy unverifiedPolicy()
    {
        return new CompositionPersistencePolicy(ACCOUNT, CompositionPersistencePolicy.Permission.UNVERIFIED,
                        Optional.empty());
    }

    @Override
    protected Control createContents(Composite parent)
    {
        var area = new Composite(parent, SWT.NONE);
        GridLayoutFactory.swtDefaults().numColumns(1).applyTo(area);

        var description = new Label(area, SWT.WRAP);
        description.setText(Messages.PrefEulerpoolDescription);
        GridDataFactory.fillDefaults().grab(true, false).hint(450, SWT.DEFAULT).applyTo(description);

        var label = new Label(area, SWT.NONE);
        label.setText(Messages.PrefTitleAPIKey);
        key = new Text(area, SWT.BORDER | SWT.PASSWORD | SWT.SINGLE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(key);
        key.setTextLimit(8192);
        // Never prefill a stored secret, including when reopening the page.
        key.addDisposeListener(event -> key.setText("")); //$NON-NLS-1$

        button(area, Messages.PrefEulerpoolSaveSecure, () -> update(credentials.replaceSecure(ACCOUNT, key.getText())));
        button(area, Messages.PrefEulerpoolUseSession, () -> update(credentials.replaceSession(ACCOUNT, key.getText())));
        button(area, Messages.PrefEulerpoolRemove, () -> update(credentials.remove(ACCOUNT)));

        var persistenceLabel = new Label(area, SWT.NONE);
        persistenceLabel.setText(Messages.PrefEulerpoolPersistence);
        persistence = new Button(area, SWT.CHECK);
        persistence.setText(Messages.PrefEulerpoolPersistenceRetain);
        persistence.setSelection(persistencePreferences.get(ACCOUNT).orElse(true));
        persistence.setEnabled(persistencePolicy.permission() == CompositionPersistencePolicy.Permission.PERMITTED);
        persistence.addSelectionListener(SelectionListener.widgetSelectedAdapter(event -> {
            persistencePreferences.set(ACCOUNT, persistence.getSelection());
            updatePersistenceStatus();
        }));

        persistenceStatus = new Label(area, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).hint(450, SWT.DEFAULT).applyTo(persistenceStatus);

        status = new Label(area, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).hint(450, SWT.DEFAULT).applyTo(status);
        updateStatus();
        updatePersistenceStatus();
        return area;
    }

    private void button(Composite area, String label, Runnable action)
    {
        var button = new Button(area, SWT.PUSH);
        button.setText(label);
        button.addSelectionListener(SelectionListener.widgetSelectedAdapter(event -> action.run()));
    }

    private void update(Result result)
    {
        setErrorMessage(switch (result)
        {
            case INVALID_KEY -> Messages.PrefEulerpoolInvalidKey;
            case STORAGE_UNAVAILABLE -> Messages.PrefEulerpoolUnavailable;
            case DELETION_FAILED -> Messages.PrefEulerpoolDeletionFailed;
            default -> null;
        });
        if (result == Result.STORED || result == Result.SESSION_ONLY || result == Result.REMOVED
                        || credentials.state(ACCOUNT).mode() == CompositionCredentials.Mode.SESSION_ONLY)
            key.setText(""); //$NON-NLS-1$
        updateStatus();
    }

    private void updatePersistenceStatus()
    {
        var effective = persistencePolicy.effectiveModeDetail(persistencePreferences.get(ACCOUNT));
        var mode = effective.mode() == CompositionPersistencePolicy.StorageMode.PERSISTENT
                        ? Messages.PrefEulerpoolPersistenceModePersistent
                        : Messages.PrefEulerpoolPersistenceModeSessionOnly;
        var reason = switch (effective.reason())
        {
            case PERMITTED -> null;
            case USER_OPT_OUT -> Messages.PrefEulerpoolPersistenceReasonUserOptOut;
            case POLICY_PROHIBITED -> Messages.PrefEulerpoolPersistenceReasonPolicyProhibited;
            case POLICY_UNVERIFIED -> Messages.PrefEulerpoolPersistenceReasonPolicyUnverified;
        };
        persistenceStatus.setText(reason == null ? mode : mode + " " + reason); //$NON-NLS-1$
        persistenceStatus.getParent().layout(true, true);
    }

    private void updateStatus()
    {
        status.setText(switch (credentials.state(ACCOUNT).mode())
        {
            case UNCONFIGURED -> Messages.PrefEulerpoolUnconfigured;
            case SECURE -> Messages.PrefEulerpoolSecure;
            case SESSION_ONLY -> Messages.PrefEulerpoolSession;
            case UNAVAILABLE -> Messages.PrefEulerpoolUnavailable;
        });
        status.getParent().layout(true, true);
    }
}
