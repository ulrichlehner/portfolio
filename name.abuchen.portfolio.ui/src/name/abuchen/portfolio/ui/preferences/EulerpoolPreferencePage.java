package name.abuchen.portfolio.ui.preferences;

import java.util.Objects;

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
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;
import name.abuchen.portfolio.ui.Messages;

/** No field editor: keys must never enter the dialog's ordinary preference store. */
public class EulerpoolPreferencePage extends PreferencePage
{
    public static final ProviderAccount ACCOUNT = new ProviderAccount(EulerpoolFundCompositionProvider.ID, "default"); //$NON-NLS-1$

    private final CompositionCredentials credentials;
    private Text key;
    private Label status;

    public EulerpoolPreferencePage()
    {
        this(CompositionCredentials.local());
    }

    public EulerpoolPreferencePage(CompositionCredentials credentials)
    {
        this.credentials = Objects.requireNonNull(credentials);
        setTitle(Messages.PrefTitleEulerpool);
        noDefaultAndApplyButton();
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

        status = new Label(area, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).hint(450, SWT.DEFAULT).applyTo(status);
        updateStatus();
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
