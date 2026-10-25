package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Lets Home see which app is playing, for the Home center's Now Playing row (home_center). Android only shows the
 * playing media sessions to an app whose notification listener the user allowed; this one reads no notifications.
 * Allowed over ADB: cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.MediaListener
 */
public final class MediaListener extends NotificationListenerService {
}
