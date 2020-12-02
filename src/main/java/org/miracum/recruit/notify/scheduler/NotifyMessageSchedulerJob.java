package org.miracum.recruit.notify.scheduler;

import org.miracum.recruit.notify.logging.LogMethodCalls;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Job necessary for quartz scheduler. */
@Component
public class NotifyMessageSchedulerJob implements Job {

  @Autowired private NotifyMessageSchedulerService jobService;

  @Override
  @LogMethodCalls
  public void execute(JobExecutionContext context) {
    String jobName = context.getJobDetail().getKey().getName();
    jobService.executeMessageDistributionJob(jobName);
  }
}
