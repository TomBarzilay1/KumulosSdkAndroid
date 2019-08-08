package com.kumulos.android;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

class InAppJavaScriptInterface {

    private static final String TAG = InAppJavaScriptInterface.class.getName();

    @JavascriptInterface
    public void postClientMessage(String msg) {

        String messageType = null;
        JSONObject data = null;
        try{
            JSONObject message = new JSONObject(msg);
            messageType = message.getString("type");
            data = message.optJSONObject("data");
        }
        catch(JSONException e){
            Log.d(TAG, "Incorrect message format: "+msg);
            return;
        }

        switch(messageType){
            case "READY":
                InAppMessagePresenter.clientReady();
                return;
            case "MESSAGE_OPENED":
                InAppMessagePresenter.messageOpened();
                return;
            case "MESSAGE_CLOSED":
                InAppMessagePresenter.messageClosed();
                return;
            case "EXECUTE_ACTIONS":

                List<ExecutableAction> actions = this.parseButtonActionData(data);
                for(ExecutableAction a : actions){
                    Log.d("vlad", a.toString());
                }

                this.executeActions(actions);
                //InAppMessagePresenter.executeActions(actions);
                return;
            default:
                Log.d(TAG, "Unknown message type: "+messageType);
        }
    }

    private void executeActions(List<ExecutableAction> actions){
        InAppMessagePresenter.closeCurrentMessage();

        for(ExecutableAction a : actions){
            switch(a.getType()){
                case "subscribeToChannel":
                    PushSubscriptionManager psm = new PushSubscriptionManager();
                    psm.subscribe(Kumulos.applicationContext, new String[]{a.getChannelUuid()});
                    break;
                case "trackConversionEvent":
                    Kumulos.trackEvent(Kumulos.applicationContext, a.getEventType(), new JSONObject());
                    break;
            }
        }

        WeakReference<Activity> currentActivityRef = InAppActivityLifecycleWatcher.getCurrentActivity();
        if (currentActivityRef == null){
            return;
        }
        Activity currentActivity = currentActivityRef.get();

        for(ExecutableAction a : actions){
            switch(a.getType()){
                case "openUrl":
                    this.openUrl(currentActivity, a.getUrl());
                    return;
                case "deepLink":
                    //TODO: beda
                case "requestAppStoreRating":
                    Log.d("vlad",currentActivity.getPackageName() );
                    this.openUrl(currentActivity, "market://details?id=" + currentActivity.getPackageName());
                    break;
            }
        }
    }

    private void openUrl(Activity currentActivity, String uri){
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        if (browserIntent.resolveActivity(currentActivity.getPackageManager()) != null) {
            currentActivity.startActivity(browserIntent);
        }
    }

    private List<ExecutableAction> parseButtonActionData(JSONObject data){
        Log.d("vlad", data.toString());
        List<ExecutableAction> actions = new ArrayList<>();


            JSONArray rawActions = data.optJSONArray("actions");
            Log.d("vlad", rawActions.toString());



        for (int i=0; i< rawActions.length(); i++){
            JSONObject rawAction = rawActions.optJSONObject(i);
            Log.d("vlad", rawAction.toString());

            String actionType = rawAction.optString("type", null);
            JSONObject rawActionData = rawAction.optJSONObject("data");

            ExecutableAction action = new ExecutableAction();
            action.setType(actionType);


            switch(actionType){
                case "subscribeToChannel":
                    String channelUuid = rawActionData.optString("channelUuid");
                    action.setChannelUuid(channelUuid);
                    break;
                case "trackConversionEvent":
                    String eventType = rawActionData.optString("eventType");
                    action.setEventType(eventType);
                    break;
                case "openUrl":
                    String url = rawActionData.optString("url");
                    action.setUrl(url);
                    break;
                case "deepLink":
                    String deepLink = rawActionData.optString("deepLink");
                    action.setDeepLink(deepLink);
                    break;
                case "closeMessage":
                case "requestAppStoreRating":
                default:
            }

            actions.add(action);
        }

        return actions;
    }

    private static class ExecutableAction{
        String type;

        String url;
        String channelUuid;
        String eventType;
        String deepLink;


        void setType(String type){
            this.type = type;
        }

        void setChannelUuid(String channelUuid){
            this.channelUuid = channelUuid;
        }

        void setEventType(String eventType){
            this.eventType = eventType;
        }

        void setUrl(String url){
            this.url = url;
        }

        void setDeepLink(String deepLink){
            this.deepLink = deepLink;
        }

        public String getType() {
            return type;
        }

        public String getUrl() {
            return url;
        }

        public String getChannelUuid() {
            return channelUuid;
        }

        public String getEventType() {
            return eventType;
        }

        public String getDeepLink() {
            return deepLink;
        }

        @Override
        public String toString(){
            return type  + " "+ (url ==null ? " null " : url)+ " "+(channelUuid ==null ? " null " : channelUuid)+ " "+ (eventType ==null ? " null " : eventType)+ " "+(deepLink ==null ? " null " : deepLink);
        }

    }




}