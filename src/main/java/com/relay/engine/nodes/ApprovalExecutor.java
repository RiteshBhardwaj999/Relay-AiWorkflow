package com.relay.engine.nodes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.common.Ids;
import com.relay.run.Approval;
import com.relay.run.ApprovalRepository;
import com.relay.run.ApprovalStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Pauses the run for a human decision. First execution creates a pending {@link Approval} and
 * returns {@link ExecResult#wait} (engine → {@code waiting_approval}). When the run resumes after a
 * decision, this node re-executes and reads the recorded decision: approved ⇒ continue, rejected ⇒
 * fail. The decision lives in the database — nothing an AI node outputs can create or alter it.
 */
@Component
public class ApprovalExecutor implements NodeExecutor {

    private final ApprovalRepository approvals;
    private final ObjectMapper mapper;

    public ApprovalExecutor(ApprovalRepository approvals, ObjectMapper mapper) {
        this.approvals = approvals;
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return "approval";
    }

    @Override
    public ExecResult execute(NodeContext ctx) {
        Optional<Approval> existing = approvals.findByRunId(ctx.run().getRunId()).stream()
                .filter(a -> a.getNodeId().equals(ctx.nodeId()))
                .findFirst();

        if (existing.isEmpty()) {
            String message = ctx.param("message");
            approvals.save(new Approval(Ids.approvalId(), ctx.run().getRunId(), ctx.nodeId(),
                    message == null ? "Approval required" : message));
            return ExecResult.wait(waitingOutput());
        }

        Approval approval = existing.get();
        return switch (approval.getStatus()) {
            case approved -> {
                ObjectNode output = mapper.createObjectNode();
                output.put("decision", "approved");
                output.put("decided_by", approval.getDecidedBy());
                yield ExecResult.continueTo(output, ctx.next());
            }
            case rejected -> ExecResult.fail("approval was rejected");
            case pending -> ExecResult.wait(waitingOutput());
        };
    }

    private ObjectNode waitingOutput() {
        ObjectNode output = mapper.createObjectNode();
        output.put("status", "waiting_approval");
        return output;
    }
}
