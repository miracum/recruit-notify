package org.miracum.recruit.notify.scheduler;

import lombok.Data;
import org.quartz.CronExpression;

/** Naming and Expressions to describe job and trigger. */
@Data
public class SchedulerData {
  private String jobName;
  private String groupName;
  private CronExpression cronExpression;
  private String triggerName;
  private String triggerGroup;
}
