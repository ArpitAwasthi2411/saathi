package com.arpit.saathi;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Background worker. Android runs it when there is a network, even with the app closed. */
public class ProcessJob extends JobService {
    public static final int JOB_SORT = 1;
    public static final int JOB_RESEARCH = 2;
    public static final int JOB_TEAM = 3;

    public static void kick(Context c, int jobId) {
        JobInfo.Builder b = new JobInfo.Builder(jobId, new ComponentName(c, ProcessJob.class));
        boolean wifi = jobId == JOB_RESEARCH && Prefs.wifiOnly(c);
        b.setRequiredNetworkType(wifi ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY);
        b.setBackoffCriteria(60000, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
        c.getSystemService(JobScheduler.class).schedule(b.build());
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context app = getApplicationContext();
        new Thread(() -> {
            boolean retry = false;
            try {
                if (params.getJobId() == JOB_SORT) {
                    retry = Processor.sortPending(app);
                    if (!Db.get(app).byState(1).isEmpty()) kick(app, JOB_RESEARCH);
                } else if (params.getJobId() == JOB_TEAM) {
                    retry = TeamRunner.runPending(app);
                } else {
                    retry = Processor.researchPending(app);
                }
            } catch (Throwable t) {
                retry = true;
            }
            jobFinished(params, retry);
        }, "saathi-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
