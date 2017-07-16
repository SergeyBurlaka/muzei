package com.google.android.apps.muzei.featuredart;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.JobIntentService;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.muzei.api.provider.Artwork;
import com.google.android.apps.muzei.api.provider.ProviderContract;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;

import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * Job that updates the FeaturedArtProvider with new artwork
 */
public class FeaturedArtJobIntentService extends JobIntentService {
    private static final String TAG = "FeaturedArtJob";

    public static final String PREF_NEXT_UPDATE_MILLIS = "next_update_millis";
    public static final String KEY_INITIAL_LOAD
            = "com.google.android.apps.muzei.featuredart.INITIAL_LOAD";

    private static final String QUERY_URL = "http://muzeiapi.appspot.com/featured?cachebust=1";

    private static final String KEY_IMAGE_URI = "imageUri";
    private static final String KEY_TITLE = "title";
    private static final String KEY_BYLINE = "byline";
    private static final String KEY_ATTRIBUTION = "attribution";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_DETAILS_URI = "detailsUri";
    private static final int MAX_JITTER_MILLIS = 20 * 60 * 1000;

    private static final Random sRandom = new Random();

    private static final SimpleDateFormat sDateFormatTZ
            = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
    private static final SimpleDateFormat sDateFormatLocal
            = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
    static {
        sDateFormatTZ.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    @Override
    protected void onHandleWork(@NonNull final Intent intent) {
        JSONObject jsonObject;
        try {
            jsonObject = fetchJsonObject(QUERY_URL);
            if (jsonObject == null) {
                return;
            }
            String token = jsonObject.optString(KEY_TOKEN);
            Artwork.Builder builder = new Artwork.Builder()
                    .token(token)
                    .title(jsonObject.optString(KEY_TITLE))
                    .byline(jsonObject.optString(KEY_BYLINE))
                    .attribution(jsonObject.optString(KEY_ATTRIBUTION));

            String imageUri = jsonObject.optString(KEY_IMAGE_URI);
            if (!TextUtils.isEmpty(imageUri)) {
                builder.persistentUri(Uri.parse(imageUri));
                // Use the image uri as the token if there isn't a specified token
                if (TextUtils.isEmpty(token)) {
                    builder.token(imageUri);
                }
            }
            String detailsUri = jsonObject.optString(KEY_DETAILS_URI);
            if (!TextUtils.isEmpty(detailsUri)) {
                builder.webUri(Uri.parse(detailsUri));
            }
            boolean initialLoad = intent.getBooleanExtra(KEY_INITIAL_LOAD, false);
            if (initialLoad) {
                // Keep the initial artwork until we've loaded a second piece of real artwork
                ProviderContract.Artwork.addArtwork(this, FeaturedArtProvider.class, builder.build());
            } else {
                // Use setArtwork to clear out previous artwork, ensuring everyone is on today's
                ProviderContract.Artwork.setArtwork(this, FeaturedArtProvider.class, builder.build());
            }
        } catch (JSONException | IOException e) {
            Log.e(TAG, "Error reading JSON", e);
            return;
        }

        Date nextTime = null;
        String nextTimeStr = jsonObject.optString("nextTime");
        if (!TextUtils.isEmpty(nextTimeStr)) {
            int len = nextTimeStr.length();
            if (len > 4 && nextTimeStr.charAt(len - 3) == ':') {
                nextTimeStr = nextTimeStr.substring(0, len - 3) + nextTimeStr.substring(len - 2);
            }
            try {
                nextTime = sDateFormatTZ.parse(nextTimeStr);
            } catch (ParseException e) {
                try {
                    sDateFormatLocal.setTimeZone(TimeZone.getDefault());
                    nextTime = sDateFormatLocal.parse(nextTimeStr);
                } catch (ParseException e2) {
                    Log.e(TAG, "Can't schedule update; "
                            + "invalid date format '" + nextTimeStr + "'", e2);
                }
            }
        }

        long nextUpdateMillis = nextTime != null
                ? nextTime.getTime() + sRandom.nextInt(MAX_JITTER_MILLIS) // jitter by up to N milliseconds
                : System.currentTimeMillis() + 12 * 60 * 60 * 1000; // No next time, default to checking in 12 hours
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        sp.edit().putLong(PREF_NEXT_UPDATE_MILLIS, nextUpdateMillis).apply();
    }

    private JSONObject fetchJsonObject(final String url) throws IOException, JSONException {
        OkHttpClient client = new OkHttpClient.Builder().build();

        Request request = new Request.Builder()
                .url(url)
                .build();
        String json = client.newCall(request).execute().body().string();
        JSONTokener tokener = new JSONTokener(json);
        Object val = tokener.nextValue();
        if (!(val instanceof JSONObject)) {
            throw new JSONException("Expected JSON object.");
        }
        return (JSONObject) val;
    }
}
