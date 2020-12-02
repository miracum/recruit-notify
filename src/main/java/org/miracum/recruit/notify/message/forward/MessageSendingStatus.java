package org.miracum.recruit.notify.message.forward;

import java.util.List;
import lombok.Data;

/**
 * Data structure contains of list of IDs of communication resources that were either sent
 * successfully and will be updated as completed or failed during sending and will be marked as
 * entered-in-error.
 */
@Data
public class MessageSendingStatus {
  List<String> messagesSentSuccessfully;
  List<String> messagesSentFailed;
}
