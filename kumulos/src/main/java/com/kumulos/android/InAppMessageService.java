package com.kumulos.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.android.gms.gcm.GcmNetworkManager;
import com.google.android.gms.gcm.GcmTaskService;
import com.google.android.gms.gcm.PeriodicTask;
import com.google.android.gms.gcm.TaskParams;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import android.util.Pair;

import org.json.JSONException;
import org.json.JSONObject;

class InAppMessageService {

    private InAppRequestService reqServ = new InAppRequestService();
    static final String EVENT_TYPE_MESSAGE_OPENED = "k.message.opened";
    private static final String EVENT_TYPE_MESSAGE_DELIVERED = "k.message.delivered";
    static final int MESSAGE_TYPE_IN_APP = 2;
    private Context mContext;

    InAppMessageService(Context context){
        mContext = context;
    }

    void fetch(Integer tickleId){
        Log.d("vlad", "thread: "+Thread.currentThread().getName());

        SharedPreferences preferences = mContext.getSharedPreferences("kumulos_prefs", Context.MODE_PRIVATE);
        long millis = preferences.getLong("last_sync_time", 0L);
        Date lastSyncTime = millis == 0 ? null : new Date(millis);

        //lastSyncTime = null;//to remove time filtering


        int tiid = tickleId == null ? 0 : tickleId;
        Log.d("vlad", "fetch called!!! tickleId: "+tiid);
        FetchCallback callback = new FetchCallback(tickleId);
        reqServ.readInAppMessages(mContext, callback, lastSyncTime);
    }


    void readMessages(boolean fromBackground, Integer tickleId){

        Callable<List<InAppMessage>> task = new InAppContract.ReadInAppMessagesCallable(mContext);
        final Future<List<InAppMessage>> future = Kumulos.executorService.submit(task);

        List<InAppMessage> unreadMessages;
        try {
            unreadMessages = future.get();
        } catch (InterruptedException | ExecutionException ex) {
            return;
        }

        if (tickleId != null) {
            Log.d("vlad", "readMessages with tickle id : " + tickleId);
        }


        List<InAppMessage> itemsToPresent = new ArrayList<>();
        for(InAppMessage message: unreadMessages){
            if (message.getPresentedWhen().equals("immediately") || (fromBackground && message.getPresentedWhen().equals("next-open")) || Integer.valueOf(message.getInAppId()).equals(tickleId)){
                itemsToPresent.add(message);
            }
        }

        //TODO: if tickleId != null, and message with it not present, extra fetch

        InAppMessagePresenter.getInstance().presentMessages(itemsToPresent, tickleId);//TODO: can multiple threads call this simultaneously?




    }



    private void storeLastSyncTime(List<InAppMessage> inAppMessages){

        Date maxUpdatedAt = inAppMessages.get(0).getUpdatedAt();

        for (int i=1; i<inAppMessages.size();i++){
            Date messageUpdatedAt = inAppMessages.get(i).getUpdatedAt();
            if (messageUpdatedAt.after(maxUpdatedAt)){
                maxUpdatedAt = messageUpdatedAt;
            }
        }

        SharedPreferences prefs = mContext.getSharedPreferences("kumulos_prefs", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putLong("last_sync_time", maxUpdatedAt.getTime());
        editor.apply();
    }

    private boolean isBackground(){
        return InAppActivityLifecycleWatcher.getCurrentActivity() == null;
    }



    private class FetchCallback extends Kumulos.ResultCallback<List<InAppMessage>> {

        Integer mTickleId;

        FetchCallback(Integer tickleId){
            this.mTickleId = tickleId;
        }

        @Override
        public void onSuccess(List<InAppMessage> inAppMessages) {


            //TODO: sometimes (especially initial fetch) TASK run happens same time as push received, results in 2 syncs. Coincidence? Don't think so
            Log.d("vlad", "FETCH ON SUCCESS");
            if (inAppMessages.isEmpty()){
                Log.d("vlad", "empty");
                return;
            }

            InAppMessageService.this.storeLastSyncTime(inAppMessages);


            Callable<Pair<List<InAppMessage>, List<Integer>>> task = new InAppContract.SaveInAppMessagesCallable(mContext, inAppMessages);
            final Future<Pair<List<InAppMessage>, List<Integer>>> future = Kumulos.executorService.submit(task);

            List<InAppMessage> unreadMessages;
            List<Integer> deliveredIds;
            try {
                Pair<List<InAppMessage>, List<Integer>> p = future.get();
                unreadMessages = p.first;
                deliveredIds = p.second;
                Log.d("vlad", ""+unreadMessages.size());
            } catch (InterruptedException | ExecutionException ex) {
                return;
            }

            this.trackDeliveredEvents(deliveredIds);

            Log.d("vlad", "thread: "+Thread.currentThread().getName());

            if (InAppActivityLifecycleWatcher.isBackground()){
                Log.d("vlad", "present, but bg");
                return;
            }

            List<InAppMessage> itemsToPresent = new ArrayList<>();
            for(InAppMessage message: unreadMessages){
                if (message.getPresentedWhen().equals("immediately") || Integer.valueOf(message.getInAppId()).equals(mTickleId)){
                    itemsToPresent.add(message);
                }
            }
            Log.d("vlad", "size to present: "+itemsToPresent.size());

            InAppMessagePresenter.getInstance().presentMessages(itemsToPresent, mTickleId);//TODO: can multiple threads call this simultaneously?

        }

        private void trackDeliveredEvents( List<Integer> deliveredIds ){

            JSONObject params = new JSONObject();

            for (Integer deliveredId: deliveredIds){
                try {
                    params.put("type", InAppMessageService.MESSAGE_TYPE_IN_APP);
                    params.put("id", deliveredId);

                    Kumulos.trackEvent(mContext, InAppMessageService.EVENT_TYPE_MESSAGE_DELIVERED, params);//TODO: does it matter which context passed. consistency?
                }
                catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void onFailure(Exception e) {
            Log.d("vlad", e.getMessage());
        }
    }


}




