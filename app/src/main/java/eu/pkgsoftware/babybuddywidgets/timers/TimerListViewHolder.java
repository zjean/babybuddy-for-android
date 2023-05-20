package eu.pkgsoftware.babybuddywidgets.timers;

import android.os.Handler;

import com.squareup.phrase.Phrase;

import org.jetbrains.annotations.NotNull;

import java.util.Date;

import androidx.recyclerview.widget.RecyclerView;
import eu.pkgsoftware.babybuddywidgets.BaseFragment;
import eu.pkgsoftware.babybuddywidgets.NotesEditorLogic;
import eu.pkgsoftware.babybuddywidgets.R;
import eu.pkgsoftware.babybuddywidgets.databinding.NotesEditorBinding;
import eu.pkgsoftware.babybuddywidgets.databinding.QuickTimerEntryBinding;
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient;
import eu.pkgsoftware.babybuddywidgets.networking.RequestCodeFailure;
import eu.pkgsoftware.babybuddywidgets.utils.Promise;
import eu.pkgsoftware.babybuddywidgets.widgets.SwitchButtonLogic;

public class TimerListViewHolder extends RecyclerView.ViewHolder {
    private final @NotNull QuickTimerEntryBinding binding;

    private final @NotNull BaseFragment baseFragment;
    private final BabyBuddyClient client;
    private final Handler timerHandler;
    private final TimerControlInterface timerControl;

    private final SwitchButtonLogic notesEditorSwitch;
    private final NotesEditorLogic notesEditor;

    private SwitchButtonLogic startStopLogic = null;

    private BabyBuddyClient.Timer timer = null;
    private Long timerStartTime = null;

    private boolean isClosed = false;

    private String padToLen(String s, char c, int length) {
        StringBuilder sBuilder = new StringBuilder(s);
        while (sBuilder.length() < length) {
            sBuilder.insert(0, c);
        }
        return sBuilder.toString();
    }

    private boolean newUpdatedPosted = false;

    private void updateTimerTime() {
        if (timerStartTime == null) {
            binding.currentTimerTime.setText("");
        } else {
            long diff = System.currentTimeMillis() + timerStartTime;

            int seconds = (int) diff / 1000;
            int minutes = seconds / 60;
            int hours = minutes / 60;

            binding.currentTimerTime.setText(
                "HH:MM:ss"
                    .replaceAll("HH", "" + hours)
                    .replaceAll("MM", padToLen("" + (minutes % 60), '0', 2))
                    .replaceAll("ss", padToLen("" + (seconds % 60), '0', 2))
            );

            if (!newUpdatedPosted) {
                timerHandler.postDelayed(() -> {
                    newUpdatedPosted = false;
                    if (!isClosed) {
                        updateTimerTime();
                    }
                }, 500);
                newUpdatedPosted = true;
            }
        }
    }

    public TimerListViewHolder(
        BaseFragment baseFragment,
        QuickTimerEntryBinding binding,
        TimerControlInterface timerControl
    ) {
        super(binding.getRoot());

        this.baseFragment = baseFragment;
        this.binding = binding;
        this.timerControl = timerControl;

        client = baseFragment.getMainActivity().getClient();

        binding.currentTimerTime.setText("");
        timerHandler = new Handler(baseFragment.getMainActivity().getMainLooper());

        notesEditorSwitch = new SwitchButtonLogic(
            binding.addNoteButton, binding.removeNoteButton, false
        );
        NotesEditorBinding notesBinding = NotesEditorBinding.inflate(
            baseFragment.getMainActivity().getLayoutInflater()
        );
        binding.verticalRoot.addView(notesBinding.getRoot());
        notesEditor = new NotesEditorLogic(
            baseFragment.getMainActivity(), notesBinding, false
        );
        notesEditorSwitch.addStateListener((v, userTriggered) -> notesEditor.setVisible(v));

        startStopLogic = new SwitchButtonLogic(
            binding.appStartTimerButton,
            binding.appStopTimerButton,
            false
        );
        startStopLogic.addStateListener(
            (active, userClicked) -> {
                if (timer == null) {
                    return;
                }
                if (!userClicked) {
                    return;
                }

                if (active) {
                    this.timerControl.startTimer(timer, new Promise<>() {
                        @Override
                        public void succeeded(BabyBuddyClient.Timer t) {
                            timer = t;
                            updateActiveState();
                        }

                        @Override
                        public void failed(TranslatedException s) {
                        }
                    });
                } else {
                    if (BabyBuddyClient.ACTIVITIES.index(timer.name) < 0) {
                        throw new UnsupportedOperationException("Activity does not exist: " + timer.name);
                    }
                    this.timerControl.storeActivity(
                        timer,
                        timer.name,
                        notesEditor.getText(),
                        new Promise<>() {
                            @Override
                            public void succeeded(Boolean stopped) {
                                if (stopped == null) {
                                    stopped = true;
                                }

                                if (stopped) {
                                    timer.active = false;
                                    updateActiveState();

                                    notesEditor.clearText();
                                    notesEditorSwitch.setState(false);
                                }
                            }

                            @Override
                            public void failed(Exception e) {
                                tryResolveStoreError(e);
                            }
                        }
                    );
                }
            }
        );
    }

    private void tryResolveStoreError(@NotNull Exception error) {
        String message = "" + error.getMessage();
        if (error instanceof RequestCodeFailure) {
            final RequestCodeFailure rcf = (RequestCodeFailure) error;
            if (rcf.hasJSONMessage()) {
                message = Phrase.from(baseFragment.getResources(), R.string.activity_store_failure_server_error)
                    .put("message", "Error while storing activity")
                    .put("server_message", String.join(", ", rcf.jsonErrorMessages()))
                    .format().toString();
            }
        }

        baseFragment.showQuestion(
            true,
            baseFragment.getString(R.string.activity_store_failure_message),
            message,
            baseFragment.getString(R.string.activity_store_failure_cancel),
            baseFragment.getString(R.string.activity_store_failure_stop_timer),
            b -> {
                if (!b) {
                    timerControl.stopTimer(timer, new Promise<>() {
                        @Override
                        public void succeeded(Object o) {
                            updateActiveState();
                        }

                        @Override
                        public void failed(TranslatedException s) {
                            baseFragment.showError(
                                true,
                                R.string.activity_store_failure_failed_to_stop_title,
                                R.string.activity_store_failure_failed_to_stop_message
                            );
                            updateActiveState();
                        }
                    });
                } else {
                    updateActiveState();
                }
            }
        );

        updateActiveState();
    }

    private void updateActiveState() {
        startStopLogic.setState(timer.active);
        if ((timer == null) || (!timer.active)) {
            timerStartTime = null;
        } else {
            timerStartTime = new Date().getTime() - timer.start.getTime() + client.getServerDateOffsetMillis() - System.currentTimeMillis();
        }
        updateTimerTime();
    }

    public void assignTimer(BabyBuddyClient.Timer timer) {
        if (isClosed) {
            isClosed = false;
            updateTimerTime();
        }

        this.timer = timer;

        String name = timer.readableName();
        final int activityIndex = BabyBuddyClient.ACTIVITIES.index(timer.name);
        if (activityIndex >= 0) {
            final String[] names = baseFragment.getResources().getStringArray(R.array.timerTypeNames);
            name = names[activityIndex];
        }
        binding.timerName.setText(name);

        updateActiveState();

        notesEditor.setIdentifier("timer_" + timer.id);
        notesEditorSwitch.setState(notesEditor.isVisible());
    }

    /**
     * Called if a new data frame was received from the server, but no timer-data was
     * changed.
     */
    public void updateNoChange() {
        // We might have "short circuited" the timer-active state. If this was the case,
        // re-enable the timer now!
        updateActiveState();
    }

    public BabyBuddyClient.Timer getTimer() {
        return timer.clone();
    }

    public void close() {
        timerStartTime = null;
        isClosed = true;
    }
}
