/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.jam;

import android.view.View;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/** Resolve native song options with the participant's account, without enqueueing or playing. */
public final class JamMenu {

  public interface Row {
    Object patch_jamMenuItem();
    void patch_jamShowMenu(View anchor, Object item);
  }

  private static final ExecutorService loader = Executors.newSingleThreadExecutor();
  private static boolean loading;

  public static void bind(View view, Row row, Object item) {
    if (JamMirror.selection(item) < 0) return;
    view.setOnLongClickListener(anchor -> {
      if (JamMirror.selection(item) < 0) return false;
      if (loading) return true;
      loading = true;
      anchor.getContext();
      Utils.showToastLong("Loading song options…");
      loader.execute(() -> {
        Object resolved = null;
        Future<?> request = null;
        try {
          YtmBridge.QueueAccess access = YtmBridge.access();
          String video = access.patch_jamVideoId(item);
          request = access.patch_jamRequestMenu(
            QueueCommand.menuRequest(video)
          );
          for (Object candidate : access.patch_jamMenuItems(
            request.get(10, TimeUnit.SECONDS)
          )) {
            if (video.equals(access.patch_jamVideoId(candidate))) {
              resolved = candidate;
              break;
            }
          }
        } catch (Exception error) {
          if (request != null) request.cancel(true);
          Logger.printException(() -> "Song menu lookup failed", error);
        }
        Object ready = resolved;
        Utils.runOnMainThread(() -> {
          loading = false;
          if (
            !anchor.isAttachedToWindow() ||
            row.patch_jamMenuItem() != item ||
            JamMirror.selection(item) < 0
          ) return;
          if (ready == null) {
            anchor.getContext();
            Utils.showToastLong("Song options unavailable; try again");
            return;
          }
          try {
            row.patch_jamShowMenu(anchor, ready);
          } catch (Exception error) {
            Logger.printException(() -> "Song menu failed", error);
            anchor.getContext();
            Utils.showToastLong("Could not open song options");
          }
        });
      });
      // Consume the hold, so releasing it cannot become a playback click.
      return true;
    });
  }
}
