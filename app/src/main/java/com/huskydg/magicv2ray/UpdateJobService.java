package com.huskydg.magicv2ray;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Daily background update check (scheduled by {@link UpdateChecker#syncJob}). */
public class UpdateJobService extends JobService {

    @Override
    public boolean onStartJob(final JobParameters params) {
        if (!UpdateChecker.autoEnabled(this)) {
            UpdateChecker.syncJob(this);        // cancels the job
            return false;
        }
        UpdateChecker.runAuto(getApplicationContext(), () -> jobFinished(params, false));
        return true;                            // work continues on a worker thread
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;                           // the next period tries again
    }
}
