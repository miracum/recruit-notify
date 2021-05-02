package org.miracum.recruit.notify.scheduler;

import static net.logstash.logback.argument.StructuredArguments.kv;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class NotifyMessageSchedulerJob implements Job {
  private static final Logger LOG = LoggerFactory.getLogger(NotifyMessageSchedulerJob.class);

  @Autowired private NotifyMessageSchedulerService jobService;

  @Override
  public void execute(JobExecutionContext context) {
    LOG.debug(
        "scheduled execution time reached for {} {} @ {} {}",
        kv("job", context.getJobDetail().getKey().getName()),
        kv("jobGroup", context.getJobDetail().getKey().getGroup()),
        kv("trigger", context.getTrigger().getKey().getName()),
        kv("triggerGroup", context.getTrigger().getKey().getGroup()));
    var jobName = context.getJobDetail().getKey().getName();
    jobService.executeMessageDistributionJob(jobName);
  }
}
