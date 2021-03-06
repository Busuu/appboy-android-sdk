package com.appboy;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.RemoteViews;
import com.appboy.configuration.XmlAppConfigurationProvider;
import com.appboy.ui.support.StringUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;

public final class AppboyGcmReceiver extends BroadcastReceiver {
  private static final String TAG = String.format("%s.%s", Constants.APPBOY, AppboyGcmReceiver.class.getName());
  private static final String GCM_RECEIVE_INTENT_ACTION = "com.google.android.c2dm.intent.RECEIVE";
  private static final String GCM_REGISTRATION_INTENT_ACTION = "com.google.android.c2dm.intent.REGISTRATION";
  private static final String GCM_ERROR_KEY = "error";
  private static final String GCM_REGISTRATION_ID_KEY = "registration_id";
  private static final String GCM_UNREGISTERED_KEY = "unregistered";
  private static final String GCM_MESSAGE_TYPE_KEY = "message_type";
  private static final String GCM_DELETED_MESSAGES_KEY = "deleted_messages";
  private static final String GCM_NUMBER_OF_MESSAGES_DELETED_KEY = "total_deleted";
  public static final String CAMPAIGN_ID_KEY = Constants.APPBOY_GCM_CAMPAIGN_ID_KEY;

  @Override
  public void onReceive(Context context, Intent intent) {
    Log.i(TAG, String.format("Received GCM message. Message: %s", intent.toString()));
    String action = intent.getAction();
    if (GCM_REGISTRATION_INTENT_ACTION.equals(action)) {
      XmlAppConfigurationProvider appConfigurationProvider = new XmlAppConfigurationProvider(context);
      handleRegistrationEventIfEnabled(appConfigurationProvider, context, intent);
    } else if (GCM_RECEIVE_INTENT_ACTION.equals(action) && isAppboyGcmMessage(intent)) {
      handleAppboyGcmMessage(context, intent);
    } else {
      Log.w(TAG, String.format("The GCM receiver received a message not sent from Appboy. Ignoring the message."));
    }
  }

  /**
   * Processes the registration/unregistration result returned from the GCM servers. If the
   * registration/unregistration is successful, this will store/clear the registration ID from the
   * device. Otherwise, it will log an error message and the device will not be able to receive GCM
   * messages.
   */
  boolean handleRegistrationIntent(Context context, Intent intent) {
    String error = intent.getStringExtra(GCM_ERROR_KEY);
    String registrationId = intent.getStringExtra(GCM_REGISTRATION_ID_KEY);

    if (error != null) {
      if ("SERVICE_NOT_AVAILABLE".equals(error)) {
        Log.e(TAG, "Unable to connect to the GCM registration server. Try again later.");
        // TODO(martin) - We should try to register again.
      } else if ("ACCOUNT_MISSING".equals(error)) {
        Log.e(TAG, "No Google account found on the phone. For pre-3.0 devices, a Google account is required on the device.");
      } else if ("AUTHENTICATION_FAILED".equals(error)) {
        Log.e(TAG, "Unable to authenticate Google account. For Android versions <4.0.4, a valid Google Play account " +
          "is required for Google Cloud Messaging to function. This phone will be unable to receive Google Cloud " +
          "Messages until the user logs in with a valid Google Play account or upgrades the operating system on this device.");
      } else if ("INVALID_SENDER".equals(error)) {
        Log.e(TAG, "One or multiple of the sender IDs provided are invalid.");
      } else if ("PHONE_REGISTRATION_ERROR".equals(error)) {
        Log.e(TAG, "Device does not support GCM.");
      } else if ("INVALID_PARAMETERS".equals(error)) {
        Log.e(TAG, "The request sent by the device does not contain the expected parameters. This phone does not " +
          "currently support GCM.");
      } else {
        Log.w(TAG, String.format("Received an unrecognised GCM registration error type. Ignoring. Error: %s", error));
      }
    } else if (registrationId != null) {
      Appboy.getInstance(context).registerAppboyGcmMessages(registrationId);
    } else if (intent.hasExtra(GCM_UNREGISTERED_KEY)) {
      Appboy.getInstance(context).unregisterAppboyGcmMessages();
    } else {
      Log.w(TAG, "The GCM registration message is missing error information, registration id, and unregistration " +
        "confirmation. Ignoring.");
      return false;
    }
    return true;
  }

  /**
   * Handles both Appboy data push GCM messages and notification messages. Notification messages are
   * posted to the notification center if the GCM message contains a title and body and the payload
   * is sent to the application via an Intent. Data push messages do not post to the notification
   * center, although the payload is forwarded to the application via an Intent as well.
   */
  boolean handleAppboyGcmMessage(Context context, Intent intent) {
    NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    String messageType = intent.getStringExtra(GCM_MESSAGE_TYPE_KEY);
    if (GCM_DELETED_MESSAGES_KEY.equals(messageType)) {
      int totalDeleted = intent.getIntExtra(GCM_NUMBER_OF_MESSAGES_DELETED_KEY, -1);
      if (totalDeleted == -1) {
        Log.e(TAG, String.format("Unable to parse GCM message. Intent: %s", intent.toString()));
      } else {
        Log.i(TAG, String.format("GCM deleted %d messages. Fetch them from Appboy.", totalDeleted));
      }
      return false;
    } else {
      Bundle extras = intent.getExtras();

      // Parsing the Appboy data extras (data push).
      Bundle appboyExtrasData = createExtrasBundle(bundleOptString(extras, Constants.APPBOY_GCM_EXTRAS_KEY, "{}"));
      extras.remove(Constants.APPBOY_GCM_EXTRAS_KEY);
      extras.putBundle(Constants.APPBOY_GCM_EXTRAS_KEY, appboyExtrasData);

      if (isNotificationMessage(intent)) {
        int notificationId = extras.getString(Constants.APPBOY_GCM_MESSAGE_TYPE_KEY).hashCode();
        extras.putInt(Constants.APPBOY_GCM_NOTIFICATION_ID, notificationId);
        XmlAppConfigurationProvider appConfigurationProvider = new XmlAppConfigurationProvider(context);
        Notification notification = createNotification(appConfigurationProvider, context,
            extras.getString(Constants.APPBOY_GCM_TITLE_KEY), extras.getString(Constants.APPBOY_GCM_CONTENT_KEY), extras);
        notificationManager.notify(Constants.APPBOY_GCM_NOTIFICATION_TAG, notificationId, notification);
        sendGcmMessageReceivedBroadcast(context, extras);
        return true;
      } else {
        sendGcmMessageReceivedBroadcast(context, extras);
        return false;
      }
    }
  }

  public static Bundle createExtrasBundle(String jsonString) {
    try {
      Bundle bundle = new Bundle();
      JSONObject json = new JSONObject(jsonString);
      Iterator keys = json.keys();
      while (keys.hasNext()) {
        String key = (String) keys.next();
        bundle.putString(key, json.getString(key));
      }
      return bundle;
    } catch (JSONException e) {
      Log.e(TAG, String.format("Unable to parse the Appboy GCM data extras."));
      return null;
    }
  }

  /**
   * Checks the intent to determine whether this is an Appboy GCM message.
   *
   * All Appboy GCM messages must contain an extras entry with key set to "_ab" and value set to "true".
   */
  public static boolean isAppboyGcmMessage(Intent intent) {
    Bundle extras = intent.getExtras();
    return extras != null && "true".equals(extras.getString(Constants.APPBOY_GCM_APPBOY_KEY));
  }

  /**
   * Checks the intent to determine whether this is a notification message or a data push.
   *
   * A notification message is an Appboy GCM message that displays a notification in the
   * notification center (and optionally contains extra information that can be used directly
   * by the app).
   *
   * A data push is an Appboy GCM message that contains only extra information that can
   * be used directly by the app.
   */
  public static boolean isNotificationMessage(Intent intent) {
    Bundle extras = intent.getExtras();
    return extras != null && extras.containsKey(Constants.APPBOY_GCM_TITLE_KEY) && extras.containsKey(Constants.APPBOY_GCM_CONTENT_KEY);
  }

  /**
   * Creates the rich notification. The notification varies based on the Android version on the
   * device, but each notification contains an icon, image, title, and content.
   *
   * Opening a notification from the notification center triggers a broadcast message to be sent.
   * The broadcast message action is <host-app-package-name>.intent.APPBOY_NOTIFICATION_OPENED.
   *
   * Note: Froyo and Gingerbread notifications are limited to one line of content.
   */
  public static Notification createNotification(XmlAppConfigurationProvider appConfigurationProvider,
                                                Context context, String title, String content, Bundle intentExtras) {
    int smallNotificationIconResourceId = appConfigurationProvider.getSmallNotificationIconResourceId();
    if (smallNotificationIconResourceId == 0) {
      Log.d(TAG, "Small notification icon resource was not found. Will use the app icon when " +
          "displaying notifications.");
      smallNotificationIconResourceId = appConfigurationProvider.getApplicationIconResourceId();
    }

    NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(context);
    notificationBuilder.setTicker(title);
    notificationBuilder.setAutoCancel(true);

    // Create broadcast intent that will fire when the notification has been opened. To action on these messages,
    // register a broadcast receiver that listens to intent <your_package_name>.intent.APPBOY_NOTIFICATION_OPENED
    // and <your_package_name>.intent.APPBOY_PUSH_RECEIVED.
    String pushOpenedAction = context.getPackageName() + ".intent.APPBOY_NOTIFICATION_OPENED";
    Intent pushOpenedIntent = new Intent(pushOpenedAction);
    if (intentExtras != null) {
      pushOpenedIntent.putExtras(intentExtras);
    }
    PendingIntent pushOpenedPendingIntent = PendingIntent.getBroadcast(context, 0, pushOpenedIntent, PendingIntent.FLAG_ONE_SHOT);
    notificationBuilder.setContentIntent(pushOpenedPendingIntent);
    // Sets the icon used in the notification bar itself.
    notificationBuilder.setSmallIcon(smallNotificationIconResourceId);
    notificationBuilder.setContentTitle(title);
    notificationBuilder.setContentText(content);

    // From Honeycomb to ICS, we can use a custom view for our notifications which will allow them to be taller than
    // the standard one line of text.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB && Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
      Resources resources = context.getResources();
      String packageName = context.getPackageName();

      int layoutResourceId = resources.getIdentifier("com_appboy_notification", "layout", packageName);
      int titleResourceId = resources.getIdentifier("com_appboy_notification_title", "id", packageName);
      int contentResourceId = resources.getIdentifier("com_appboy_notification_content", "id", packageName);
      int iconResourceId = resources.getIdentifier("com_appboy_notification_icon", "id", packageName);
      int timeViewResourceId = resources.getIdentifier("com_appboy_notification_time", "id", packageName);
      int twentyFourHourFormatResourceId = resources.getIdentifier("com_appboy_push_notification_twenty_four_hour_format", "string", packageName);
      int twelveHourFormatResourceId = resources.getIdentifier("com_appboy_push_notification_twelve_hour_format", "string", packageName);

      String twentyFourHourTimeFormat = StringUtils.getOptionalStringResource(resources,
        twentyFourHourFormatResourceId, Constants.DEFAULT_TWENTY_FOUR_HOUR_TIME_FORMAT);
      String twelveHourTimeFormat = StringUtils.getOptionalStringResource(resources,
        twelveHourFormatResourceId, Constants.DEFAULT_TWELVE_HOUR_TIME_FORMAT);

      if (layoutResourceId == 0 || titleResourceId == 0 || contentResourceId == 0 || iconResourceId == 0
        || timeViewResourceId == 0) {
        Log.w(TAG, String.format("Couldn't find all resource IDs for custom notification view, extended view will " +
          "not be used for push notifications. Received %d for layout, %d for title, %d for content, %d for icon, " +
          "and %d for time.",
          layoutResourceId, titleResourceId, contentResourceId, iconResourceId, timeViewResourceId));
      } else {
        Log.d(TAG, "Using RemoteViews for rendering of push notification.");
        RemoteViews remoteViews = new RemoteViews(packageName, layoutResourceId);
        remoteViews.setTextViewText(titleResourceId, title);
        remoteViews.setTextViewText(contentResourceId, content);
        remoteViews.setImageViewResource(iconResourceId, smallNotificationIconResourceId);

        // Custom views cannot be used as part of a RemoteViews so we're using a TextView widget instead. This
        // view will always display the time without date information (even after the day has changed).
        SimpleDateFormat timeFormat = new SimpleDateFormat(
          android.text.format.DateFormat.is24HourFormat(context) ? twentyFourHourTimeFormat : twelveHourTimeFormat);
        String notificationTime = timeFormat.format(new Date());
        remoteViews.setTextViewText(timeViewResourceId, notificationTime);
        notificationBuilder.setContent(remoteViews);
        return notificationBuilder.build();
      }
    }

    // If we're using Jelly Bean, we can use the BigTextStyle, which lets the notification layout size grow to
    // accommodate longer text.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
      Log.d(TAG, "Rendering push notification with BigTextStyle");
      return new NotificationCompat.BigTextStyle(notificationBuilder)
        .bigText(content).build();
    }
    return notificationBuilder.build();
  }

  /**
   * Creates and sends a broadcast message that can be listened for by the host app. The broadcast
   * message intent contains all of the data sent as part of the Appboy GCM message. The broadcast
   * message action is <host-app-package-name>.intent.APPBOY_PUSH_RECEIVED.
   */
  private void sendGcmMessageReceivedBroadcast(Context context, Bundle extras) {
    String pushReceivedAction = context.getPackageName() + ".intent.APPBOY_PUSH_RECEIVED";
    Intent pushReceivedIntent = new Intent(pushReceivedAction);
    if (extras != null) {
      pushReceivedIntent.putExtras(extras);
    }
    context.sendBroadcast(pushReceivedIntent);
  }

  boolean handleRegistrationEventIfEnabled(XmlAppConfigurationProvider appConfigurationProvider,
                                                   Context context, Intent intent) {
    // Only handle GCM registration events if GCM registration handling is turned on in the
    // configuration file.
    if (appConfigurationProvider.isGcmMessagingRegistrationEnabled()) {
      handleRegistrationIntent(context, intent);
      return true;
    }
    return false;
  }

  public static String bundleOptString(Bundle bundle, String key, String defaultValue) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1) {
     return bundle.getString(key, defaultValue);
    } else {
      String result = bundle.getString(key);
      if (result == null) {
        result = defaultValue;
      }
      return result;
    }
  }
}
