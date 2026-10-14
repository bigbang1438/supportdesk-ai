package com.supportdesk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.security.Principal;
import java.util.Map;
import static com.supportdesk.ApiModels.*;

@RestController @RequestMapping("/api/v1") @Validated
public class SupportController {
    private final SupportService service;
    public SupportController(SupportService service) { this.service=service; }
    private Actor actor(Principal principal) { return service.actor(principal.getName()); }
    @GetMapping("/orders") Page<OrderView> orders(Principal p,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String q) { return service.orders(actor(p),page,size,q); }
    @GetMapping("/orders/{id}") Map<String,Object> order(Principal p,@PathVariable @Positive long id) { return Map.of("data",service.order(actor(p),id)); }
    @GetMapping("/tickets") Page<TicketView> tickets(Principal p,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status) { return service.tickets(actor(p),page,size,q,status); }
    @PostMapping("/tickets") ResponseEntity<?> create(Principal p,@Valid @RequestBody TicketInput input) { var result=service.createTicket(actor(p),input);return ResponseEntity.created(URI.create("/api/v1/tickets/"+result.id())).body(Map.of("data",result)); }
    @GetMapping("/tickets/{id}") Map<String,Object> ticket(Principal p,@PathVariable @Positive long id) { return Map.of("data",service.ticket(actor(p),id)); }
    @GetMapping("/tickets/{id}/messages") Map<String,Object> messages(Principal p,@PathVariable @Positive long id) { return Map.of("data",service.messages(actor(p),id)); }
    @PostMapping("/tickets/{id}/messages") ResponseEntity<?> reply(Principal p,@PathVariable @Positive long id,@Valid @RequestBody MessageInput input) { return ResponseEntity.status(201).body(Map.of("data",service.reply(actor(p),id,input))); }
    @PatchMapping("/tickets/{id}/status") Map<String,Object> status(Principal p,@PathVariable @Positive long id,@Valid @RequestBody StatusInput input) { return Map.of("data",service.status(actor(p),id,input)); }
    @GetMapping("/tickets/{id}/events") Map<String,Object> events(Principal p,@PathVariable @Positive long id) { return Map.of("data",service.events(actor(p),id)); }
    @GetMapping("/policies") Map<String,Object> policies(Principal p) { return Map.of("data",service.policies(actor(p))); }
    @PostMapping("/policies") ResponseEntity<?> createPolicy(Principal p,@Valid @RequestBody PolicyInput input) { var result=service.createPolicy(actor(p),input);return ResponseEntity.created(URI.create("/api/v1/policies/"+result.id())).body(Map.of("data",result)); }
    @PutMapping("/policies/{id}") Map<String,Object> updatePolicy(Principal p,@PathVariable @Positive long id,@Valid @RequestBody PolicyUpdateInput input) { return Map.of("data",service.updatePolicy(actor(p),id,input)); }
    @GetMapping("/summary") Map<String,Object> summary(Principal p) { return Map.of("data",service.summary(actor(p))); }
}
