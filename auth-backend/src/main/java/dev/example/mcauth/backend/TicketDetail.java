package dev.example.mcauth.backend;

import java.util.List;
import java.util.Map;

public record TicketDetail(
        TicketListItem ticket,
        Map<String, String> fields,
        List<TicketMessageView> messages,
        List<AttachmentReference> attachments) {
}
