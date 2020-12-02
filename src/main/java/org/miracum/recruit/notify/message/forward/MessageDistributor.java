package org.miracum.recruit.notify.message.forward;

/** Distributing messages from queue. */
public interface MessageDistributor {

  void distribute(String jobKey);
}
