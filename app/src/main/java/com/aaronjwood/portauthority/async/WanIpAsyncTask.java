package com.aaronjwood.portauthority.async;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.AsyncTask;

import com.aaronjwood.portauthority.response.MainAsyncResponse;
import com.aaronjwood.portauthority.response.WanIpResult;

import java.io.IOException;
import java.lang.ref.WeakReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class WanIpAsyncTask extends AsyncTask<Void, Void, WanIpResult> {

    private static final String IPV4_SERVICE = "https://ipv4.icanhazip.com/";
    private static final String IPV6_SERVICE = "https://ipv6.icanhazip.com/";
    private final WeakReference<MainAsyncResponse> delegate;

    public WanIpAsyncTask(MainAsyncResponse delegate) {
        this.delegate = new WeakReference<>(delegate);
    }

    private String fetchIp(String url) {
        OkHttpClient httpClient = new OkHttpClient();
        Request request = new Request.Builder().url(url).build();
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                return null;
            }
            return body.string().trim();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    @SuppressLint("NewApi")
    protected WanIpResult doInBackground(Void... params) {
        String ipv4 = fetchIp(IPV4_SERVICE);
        String ipv6 = fetchIp(IPV6_SERVICE);
        return new WanIpResult(ipv4, ipv6);
    }

    @Override
    protected void onPostExecute(WanIpResult result) {
        MainAsyncResponse activity = delegate.get();
        if (activity != null) {
            activity.processFinish(result);
        }
    }
}
