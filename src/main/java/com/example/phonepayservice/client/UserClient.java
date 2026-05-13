package com.example.phonepayservice.client;

import com.example.phonepayservice.dto.User;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@FeignClient(name="Bankapplication",url="http://localhost:8080")
public interface UserClient {
    @GetMapping("/bank/all")
    public List<User> getAllUsers();
    @GetMapping("/bank/getByphno/{phno}")
    public User getUserByPhno(@PathVariable long phno);
    @PutMapping("/bank/withdrawByphno")
    public User withdrawByphno(@RequestParam long phno, @RequestParam double balance);
    @PostMapping("/bank/withdrawbyacno")
    public User withdrawbyacno(@RequestParam long phno, @RequestParam double balance);
    @PutMapping("/bank/depositByphno")
    public User depositByphno(@RequestParam long phno, @RequestParam double balance);
    @PutMapping("/bank/depositByacno")
    public User depositbyacno(@RequestParam long phno, @RequestParam double balance);
}
