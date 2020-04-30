package edu.uw.covidsafe.workmanager.periodictasks;

import android.content.Context;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import edu.uw.covidsafe.workmanager.workers.PullFromServerWorker;

public class PeriodicTasksHandler {

    private static final String PULL_SERVICE_TAG = "pullservice";
    private Context context;

    public PeriodicTasksHandler(Context context) {
        this.context = context;
    }

    Map<String, PeriodicWorkRequest> periodicWorkRequests = new HashMap<>();

    PeriodicWorkRequest periodicWorkRequest =
            new PeriodicWorkRequest.Builder(PullFromServerWorker.class, 1, TimeUnit.HOURS)
                    .addTag(PULL_SERVICE_TAG)
                    .build();

    public void initAllPeriodicRequests() {
        PeriodicWorkRequest periodicPullServiceWorkRequest =
                new PeriodicWorkRequest.Builder(PullFromServerWorker.class, 1, TimeUnit.HOURS)
                        .build();
        periodicWorkRequests.put(PULL_SERVICE_TAG, periodicPullServiceWorkRequest);
        startWorkIfNotScheduled();
    }

    private void startWorkIfNotScheduled() {
        for (Map.Entry<String, PeriodicWorkRequest> entry : periodicWorkRequests.entrySet()) {
            if (!isWorkScheduled(entry.getKey())) {
                startUniqueWork(periodicWorkRequest, entry.getKey());
            }
        }
    }

    private void startUniqueWork(PeriodicWorkRequest periodicWorkRequest, String pullServiceTag) {
        WorkManager instance = WorkManager.getInstance(context);
        instance.enqueueUniquePeriodicWork(pullServiceTag, ExistingPeriodicWorkPolicy.KEEP, periodicWorkRequest);
    }

    private boolean isWorkScheduled(String tag) {
        WorkManager instance = WorkManager.getInstance(context);
        ListenableFuture<List<WorkInfo>> statuses = instance.getWorkInfosByTag(tag);
        try {
            boolean running = false;
            List<WorkInfo> workInfoList = statuses.get();
            for (WorkInfo workInfo : workInfoList) {
                WorkInfo.State state = workInfo.getState();
                running = state == WorkInfo.State.RUNNING | state == WorkInfo.State.ENQUEUED;
            }
            return running;
        } catch (ExecutionException e) {
            e.printStackTrace();
            return false;
        } catch (InterruptedException e) {
            e.printStackTrace();
            return false;
        }
    }
}
