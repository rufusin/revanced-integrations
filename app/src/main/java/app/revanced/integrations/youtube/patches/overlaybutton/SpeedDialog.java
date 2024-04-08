package app.revanced.integrations.youtube.patches.overlaybutton;

import static app.revanced.integrations.youtube.patches.video.PlaybackSpeedPatch.overrideSpeed;
import static app.revanced.integrations.youtube.utils.ReVancedUtils.showToastShort;
import static app.revanced.integrations.youtube.utils.ResourceUtils.anim;
import static app.revanced.integrations.youtube.utils.ResourceUtils.findView;
import static app.revanced.integrations.youtube.utils.ResourceUtils.integer;
import static app.revanced.integrations.youtube.utils.StringRef.str;

import android.annotation.SuppressLint;
import android.support.constraint.ConstraintLayout;
import android.view.View;
import android.view.animation.Animation;
import android.widget.ImageView;

import java.lang.ref.WeakReference;

import app.revanced.integrations.youtube.settings.SettingsEnum;
import app.revanced.integrations.youtube.utils.LogHelper;
import app.revanced.integrations.youtube.utils.VideoHelpers;

@SuppressWarnings("unused")
public class SpeedDialog {
    volatile static boolean isButtonEnabled;
    volatile static boolean isShowing;
    volatile static boolean isScrubbed;
    static WeakReference<ImageView> buttonView = new WeakReference<>(null);
    @SuppressLint("StaticFieldLeak")
    static ConstraintLayout constraintLayout;
    static int fadeDurationFast;
    static int fadeDurationScheduled;
    static Animation fadeIn;
    static Animation fadeOut;

    public static void initialize(Object obj) {
        try {
            constraintLayout = (ConstraintLayout) obj;
            isButtonEnabled = setValue();
            ImageView imageView = findView(constraintLayout, "speed_dialog_button");

            imageView.setOnClickListener(view -> VideoHelpers.playbackSpeedDialogListener(view.getContext()));
            imageView.setOnLongClickListener(view -> {
                if (!SettingsEnum.ENABLE_SAVE_PLAYBACK_SPEED.getBoolean() ||
                        VideoHelpers.getCurrentSpeed() == SettingsEnum.DEFAULT_PLAYBACK_SPEED.getFloat()) {
                    overrideSpeed(1.0f);
                } else {
                    overrideSpeed(SettingsEnum.DEFAULT_PLAYBACK_SPEED.getFloat());
                }

                return true;
            });
            buttonView = new WeakReference<>(imageView);

            fadeDurationFast = integer("fade_duration_fast");
            fadeDurationScheduled = integer("fade_duration_scheduled");

            fadeIn = anim("fade_in");
            fadeIn.setDuration(fadeDurationFast);

            fadeOut = anim("fade_out");
            fadeOut.setDuration(fadeDurationScheduled);

            isShowing = true;
            isScrubbed = false;
            changeVisibility(false);

        } catch (Exception ex) {
            LogHelper.printException(() -> "Unable to set FrameLayout", ex);
        }
    }

    public static void changeVisibility(boolean currentVisibility) {
        ImageView imageView = buttonView.get();

        if (isShowing == currentVisibility || constraintLayout == null || imageView == null)
            return;

        isShowing = currentVisibility;

        if (isScrubbed && isButtonEnabled) {
            isScrubbed = false;
            imageView.setVisibility(View.VISIBLE);
            return;
        }

        if (currentVisibility && isButtonEnabled) {
            imageView.setVisibility(View.VISIBLE);
            imageView.startAnimation(fadeIn);
        } else if (imageView.getVisibility() == View.VISIBLE) {
            imageView.startAnimation(fadeOut);
            imageView.setVisibility(View.GONE);
        }
    }

    public static void changeVisibilityNegatedImmediate(boolean isUserScrubbing) {
        ImageView imageView = buttonView.get();

        if (constraintLayout == null || imageView == null || !isUserScrubbing)
            return;

        isShowing = false;
        isScrubbed = true;
        imageView.setVisibility(View.GONE);
    }

    public static void refreshVisibility() {
        isButtonEnabled = setValue();
    }

    private static boolean setValue() {
        return SettingsEnum.OVERLAY_BUTTON_SPEED_DIALOG.getBoolean();
    }
}
