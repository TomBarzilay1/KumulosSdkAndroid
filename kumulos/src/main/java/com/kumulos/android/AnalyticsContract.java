package com.kumulos.android;

import android.app.Activity;
import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.gcm.GcmNetworkManager;
import com.google.android.gms.gcm.OneoffTask;
import com.google.android.gms.gcm.Task;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** package */ final class AnalyticsContract {

    private static final String EVENT_TYPE_FOREGROUND = "k.fg";
    static final String EVENT_TYPE_BACKGROUND = "k.bg";
    private static final String EVENT_TYPE_CALL_HOME = "k.stats.installTracked";
    static final String EVENT_TYPE_ASSOCIATE_USER = "k.stats.userAssociated";
    static final String EVENT_TYPE_CLEAR_USER_ASSOCIATION = "k.stats.userAssociationCleared";
    static final String EVENT_TYPE_PUSH_DEVICE_REGISTERED = "k.push.deviceRegistered";
    static final String EVENT_TYPE_PUSH_DEVICE_UNSUBSCRIBED = "k.push.deviceUnsubscribed";
    static final String EVENT_TYPE_MESSAGE_DISMISSED = "k.message.dismissed";
    static final String EVENT_TYPE_MESSAGE_OPENED = "k.message.opened";
    static final String EVENT_TYPE_MESSAGE_DELIVERED = "k.message.delivered";
    static final String EVENT_TYPE_ENTERED_BEACON_PROXIMITY = "k.engage.beaconEnteredProximity";
    static final String EVENT_TYPE_LOCATION_UPDATED = "k.engage.locationUpdated";
    static final int MESSAGE_TYPE_PUSH = 1;
    static final int MESSAGE_TYPE_IN_APP = 2;

    private AnalyticsContract() {}

    /** package */ static class AnalyticsEvent {
        static final String TABLE_NAME = "events";
        static final String COL_ID = "id";
        static final String COL_UUID = "uuid";
        static final String COL_HAPPENED_AT_MILLIS = "happened_at";
        static final String COL_EVENT_TYPE = "type";
        static final String COL_PROPERTIES = "properties";
        static final String COL_USER_IDENTIFIER = "user_identifier";
    }

    /**
     * Task to record details of an event in the local DB & schedule a sync
     */
    static class TrackEventRunnable implements Runnable {

        private static final String TAG = TrackEventRunnable.class.getName();

        private Context mContext;
        private String eventType;
        private long happenedAt;
        private JSONObject properties;
        private boolean immediateFlush;

        private TrackEventRunnable() {}

        TrackEventRunnable(Context context, @NonNull String eventType, long happenedAt, @Nullable JSONObject properties, boolean immediateFlush) {
            this.mContext = context.getApplicationContext();
            this.eventType = eventType;
            this.happenedAt = happenedAt;
            this.properties = properties;
            this.immediateFlush = immediateFlush;
        }

        @Override
        public void run() {
            UUID uuid = UUID.randomUUID();
            String uuidStr = uuid.toString();

            // Record
            ContentValues values = new ContentValues();
            values.put(AnalyticsEvent.COL_EVENT_TYPE, this.eventType);
            values.put(AnalyticsEvent.COL_UUID, uuidStr);
            values.put(AnalyticsEvent.COL_HAPPENED_AT_MILLIS, this.happenedAt);
            values.put(AnalyticsEvent.COL_USER_IDENTIFIER, Kumulos.getCurrentUserIdentifier(mContext));

            String propsStr = (null == this.properties) ? null : properties.toString();
            values.put(AnalyticsEvent.COL_PROPERTIES, propsStr);

            SQLiteOpenHelper dbHelper = new AnalyticsDbHelper(mContext);
            try {
                SQLiteDatabase db = dbHelper.getWritableDatabase();
                db.insertOrThrow(AnalyticsEvent.TABLE_NAME, null, values);
                dbHelper.close();
                Kumulos.log(TAG, "Tracked event " + eventType + " with UUID " + uuidStr);
            }
            catch (SQLiteException e) {
                e.printStackTrace();
                return;
            }

            if (immediateFlush) {
                AnalyticsUploadHelper helper = new AnalyticsUploadHelper();
                AnalyticsUploadHelper.Result result = helper.flushEvents(mContext);

                if (result == AnalyticsUploadHelper.Result.SUCCESS) {
                    return;
                }
                // On failures, fall through to scheduling a background sync
            }

            // Schedule a sync
            int playServicesCheck = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(mContext);
            if (ConnectionResult.SUCCESS != playServicesCheck) {
                Kumulos.log(TAG, "Failed to schedule a sync, play services availability check resulted in: " + playServicesCheck);
                return;
            }

            KumulosConfig config = Kumulos.getConfig();
            Bundle bundle = new Bundle();
            bundle.putBundle(AnalyticsUploadService.KEY_CONFIG, config.toBundle());

            OneoffTask.Builder uploadTaskBuilder = new OneoffTask.Builder()
                    .setService(AnalyticsUploadService.class)
                    .setTag(AnalyticsUploadService.TAG)
                    .setUpdateCurrent(true)
                    .setExtras(bundle)
                    .setRequiredNetwork(OneoffTask.NETWORK_STATE_CONNECTED);

            if (BuildConfig.DEBUG) {
                uploadTaskBuilder.setExecutionWindow(20, 40);
            }
            else {
                uploadTaskBuilder.setExecutionWindow(5 * 60, 15 * 60);
            }

            try {
                OneoffTask uploadTask = uploadTaskBuilder.build();
                GcmNetworkManager.getInstance(mContext).schedule(uploadTask);
            }
            catch (Throwable e) {
                e.printStackTrace();
            }

        }
    }

    /**
     * Task to clear out synced events from the local DB
     */
    static class TrimEventsRunnable implements Runnable {

        private static final String TAG = TrimEventsRunnable.class.getName();

        private Context mContext;
        private long mUpToEventId;

        private TrimEventsRunnable() {}

        TrimEventsRunnable(Context context, long upToEventId) {
            mContext = context.getApplicationContext();
            mUpToEventId =  upToEventId;
        }

        @Override
        public void run() {
            SQLiteOpenHelper dbHelper = new AnalyticsDbHelper(mContext);

            try {
                SQLiteDatabase db = dbHelper.getWritableDatabase();

                db.delete(
                        AnalyticsEvent.TABLE_NAME,
                        AnalyticsEvent.COL_ID + " <= ?",
                        new String[]{String.valueOf(mUpToEventId)});

                dbHelper.close();
                Kumulos.log(TAG, "Trimmed events up to " + mUpToEventId + " (inclusive)");
            }
            catch (SQLiteException e) {
                Kumulos.log(TAG, "Failed to trim events up to " + mUpToEventId + " (inclusive)");
                e.printStackTrace();
            }
        }
    }

    /**
     * Records current install info for analytics
     */
    static class StatsCallHomeRunnable implements Runnable {

        private static final int SDK_TYPE = 2;
        private static final int RUNTIME_TYPE = 1;
        private static final int OS_TYPE = 3;

        private Context mContext;

        private StatsCallHomeRunnable() {}

        StatsCallHomeRunnable(Context context) {
            mContext = context.getApplicationContext();
        }

        @Override
        public void run() {
            PackageInfo packageInfo;

            try {
                packageInfo = mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), 0);
            } catch (PackageManager.NameNotFoundException e) {
                return;
            }

            KumulosConfig config = Kumulos.getConfig();
            final JSONObject finalObj;
            try {
                JSONObject app = new JSONObject()
                        .put("version", packageInfo.versionName)
                        .put("target", BuildConfig.DEBUG ? 1 : 2)
                        .put("bundle", packageInfo.packageName);

                JSONObject sdk = config.getSdkInfo();
                if (null == sdk) {
                    sdk = new JSONObject()
                            .put("id", SDK_TYPE)
                            .put("version", Kumulos.VERSION);
                }

                JSONObject runtime = config.getRuntimeInfo();
                if (null == runtime) {
                    runtime = new JSONObject()
                            .put("id", RUNTIME_TYPE)
                            .put("version", Build.VERSION.RELEASE);
                }

                JSONObject os = new JSONObject()
                        .put("id", OS_TYPE)
                        .put("version", Build.VERSION.RELEASE);

                JSONObject device = new JSONObject()
                        .put("name", Build.MODEL)
                        .put("tz", TimeZone.getDefault().getID())
                        .put("isSimulator", isEmulator())
                        .put("locale", getLocale());

                finalObj = new JSONObject()
                        .put("app", app)
                        .put("sdk", sdk)
                        .put("runtime", runtime)
                        .put("os", os)
                        .put("device", device);
            } catch (JSONException e) {
                return;
            }

            Kumulos.trackEvent(mContext, AnalyticsContract.EVENT_TYPE_CALL_HOME, finalObj);
        }

        // https://stackoverflow.com/a/33970189
        private static String getLocale() {
            Locale loc = Locale.getDefault();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return loc.toLanguageTag();
            }

            // we will use a dash as per BCP 47
            final char SEP = '-';
            String language = loc.getLanguage();
            String region = loc.getCountry();
            String variant = loc.getVariant();

            // special case for Norwegian Nynorsk since "NY" cannot be a variant as per BCP 47
            // this goes before the string matching since "NY" wont pass the variant checks
            if (language.equals("no") && region.equals("NO") && variant.equals("NY")) {
                language = "nn";
                region = "NO";
                variant = "";
            }

            if (language.isEmpty() || !language.matches("\\p{Alpha}{2,8}")) {
                language = "und";       // Follow the Locale#toLanguageTag() implementation
                // which says to return "und" for Undetermined
            } else if (language.equals("iw")) {
                language = "he";        // correct deprecated "Hebrew"
            } else if (language.equals("in")) {
                language = "id";        // correct deprecated "Indonesian"
            } else if (language.equals("ji")) {
                language = "yi";        // correct deprecated "Yiddish"
            }

            // ensure valid country code, if not well formed, it's omitted
            if (!region.matches("\\p{Alpha}{2}|\\p{Digit}{3}")) {
                region = "";
            }

            // variant subtags that begin with a letter must be at least 5 characters long
            if (!variant.matches("\\p{Alnum}{5,8}|\\p{Digit}\\p{Alnum}{3}")) {
                variant = "";
            }

            StringBuilder bcp47Tag = new StringBuilder(language);
            if (!region.isEmpty()) {
                bcp47Tag.append(SEP).append(region);
            }
            if (!variant.isEmpty()) {
                bcp47Tag.append(SEP).append(variant);
            }

            return bcp47Tag.toString();
        }

        // http://stackoverflow.com/a/21505193/543200
        // http://stackoverflow.com/a/35440927/543200
        private static boolean isEmulator() {
            return Build.FINGERPRINT.startsWith("generic")
                    || Build.FINGERPRINT.startsWith("unknown")
                    || Build.MODEL.contains("google_sdk")
                    || Build.MODEL.contains("Emulator")
                    || Build.MODEL.contains("Android SDK built for x86")
                    || Build.MANUFACTURER.contains("Genymotion")
                    || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                    || "google_sdk".equals(Build.PRODUCT)
                    || Build.PRODUCT.contains("vbox86p")
                    || Build.DEVICE.contains("Droid4X");
        }
    }

    static class ForegroundStateWatcher implements Application.ActivityLifecycleCallbacks {

        WeakReference<Context> mContextRef;
        static AtomicBoolean startNewSession;

        //IN APP
        private static WeakReference<Activity> currentActivityRef = new WeakReference<>(null);
        @Nullable
        static Activity getCurrentActivity() {
            return currentActivityRef.get();
        }
        private static int numStarted = 0;
        static boolean isBackground(){
            return numStarted == 0;
        }


        ForegroundStateWatcher(Context context) {
            mContextRef = new WeakReference<>(context);
            startNewSession = new AtomicBoolean(true);
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) { /* noop */}

        @Override
        public void onActivityStarted(Activity activity) {  /* noop */ }

        private Integer getTickleId(Activity activity){
            Intent i = activity.getIntent();
            int tickleIdExtra = i.getIntExtra(PushBroadcastReceiver.EXTRAS_KEY_TICKLE_ID, -1);
            return tickleIdExtra == -1 ? null : tickleIdExtra;
        }

        @Override
        public void onActivityResumed(Activity activity) {
            currentActivityRef = new WeakReference<>(activity);

            Integer tickleId = this.getTickleId(activity);
            if ((isBackground() || tickleId != null) && KumulosInApp.isInAppEnabled()) {
                InAppMessageService.readMessages(activity, isBackground(), tickleId);
            }
            numStarted++;

            final Context context = mContextRef.get();
            if (null == context) {
                return;
            }

            if (startNewSession.getAndSet(false)) {
                Kumulos.trackEvent(context, AnalyticsContract.EVENT_TYPE_FOREGROUND, null);
                return;
            }

            Kumulos.executorService.submit(new Runnable() {
                @Override
                public void run() {
                    GcmNetworkManager.getInstance(context).cancelAllTasks(AnalyticsBackgroundEventService.class);
                }
            });
        }

        @Override
        public void onActivityPaused(Activity activity) {
            clearCurrentActivity(activity);
            numStarted = Math.max(numStarted-1, 0);

            final Context context = mContextRef.get();
            if (null == context) {
                return;
            }

            final KumulosConfig config = Kumulos.getConfig();
            final Bundle bundle = new Bundle();

            bundle.putLong(AnalyticsBackgroundEventService.EXTRAS_KEY_TIMESTAMP, System.currentTimeMillis());
            bundle.putBundle(AnalyticsBackgroundEventService.EXTRAS_KEY_CONFIG, config.toBundle());

            Kumulos.executorService.submit(new Runnable() {
                @Override
                public void run() {
                    Task task = new OneoffTask.Builder()
                            .setExecutionWindow(config.getSessionIdleTimeoutSeconds(), config.getSessionIdleTimeoutSeconds() + 10)
                            .setService(AnalyticsBackgroundEventService.class)
                            .setTag(AnalyticsBackgroundEventService.TAG)
                            .setExtras(bundle)
                            .setRequiredNetwork(Task.NETWORK_STATE_ANY)
                            .build();

                    GcmNetworkManager.getInstance(context).schedule(task);
                }
            });
        }

        @Override
        public void onActivityStopped(Activity activity) {  /* noop */ }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {  /* noop */ }

        @Override
        public void onActivityDestroyed(Activity activity) {
            InAppMessagePresenter.maybeCloseDialog(activity);

            clearCurrentActivity(activity);
        }

        private void clearCurrentActivity(Activity activity){
            Activity currentActivity = getCurrentActivity();
            if (currentActivity == null){
                return;
            }

            if (currentActivity.hashCode() == activity.hashCode()) {
                currentActivityRef = new WeakReference<>(null);
            }
        }
    }
}
