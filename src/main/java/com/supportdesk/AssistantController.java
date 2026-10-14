package com.supportdesk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.security.Principal;
import java.util.Map;
import static com.supportdesk.AssistantModels.*;

@RestController
@RequestMapping("/api/v1")
public class AssistantController {
    private final AssistantService assistant;
    public AssistantController(AssistantService assistant) { this.assistant=assistant; }
    @GetMapping("/assistant/capabilities") Object capabilities(Principal principal) {
        return Map.of("data",assistant.capabilities(principal.getName()));
    }
    @GetMapping("/tickets/{ticket}/assistant/runs") Object runs(Principal principal,@PathVariable @Positive long ticket) {
        return Map.of("data",assistant.runs(principal.getName(),ticket));
    }
    @PostMapping("/tickets/{ticket}/assistant/runs") ResponseEntity<?> run(Principal principal,@PathVariable @Positive long ticket,@Valid @RequestBody RunInput input) {
        var run=assistant.run(principal.getName(),ticket,input);
        return ResponseEntity.created(URI.create("/api/v1/tickets/"+ticket+"/assistant/runs/"+run.id())).body(Map.of("data",run));
    }
    @PostMapping("/tickets/{ticket}/assistant/runs/{run}/publish") Object publish(Principal principal,@PathVariable @Positive long ticket,@PathVariable @Positive long run,@Valid @RequestBody PublishInput input) {
        return Map.of("data",assistant.publish(principal.getName(),ticket,run,input));
    }
    @PostMapping("/tickets/{ticket}/assistant/runs/{run}/discard") Object discard(Principal principal,@PathVariable @Positive long ticket,@PathVariable @Positive long run,@RequestBody DiscardInput input) {
        return Map.of("data",assistant.discard(principal.getName(),ticket,run));
    }
}
