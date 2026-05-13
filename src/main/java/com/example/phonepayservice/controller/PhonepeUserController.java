package com.example.phonepayservice.controller;

import com.example.phonepayservice.entity.PhonepeUser;
import com.example.phonepayservice.entity.Transaction;
import com.example.phonepayservice.service.PhonepeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/phonepe")
public class PhonepeUserController {
    @Autowired
    private PhonepeService phonepeService;
    @PostMapping("/login")
    public void login(@RequestParam long phno){
        phonepeService.login(phno);
    }
    @GetMapping("/all")
    public List<PhonepeUser> getAllTransactions(){
        return phonepeService.findAll();
    }
    @PutMapping("/sendmoney")
    public PhonepeUser sendMoney(@RequestParam long phno,@RequestParam double amount){
        return phonepeService.sendMoney(phno,amount);
    }
    @PutMapping("/makepayment")
    public PhonepeUser makePayment(@RequestParam double amount){
        return phonepeService.makePayment(amount);
    }
    @GetMapping("/transactions")
    public List<Transaction> getAllTransaction()
    {
        return phonepeService.getAllTransaction();
    }
    @GetMapping("/transactionByphno")
    public List<Transaction> getAllTransactionByphno(@RequestParam long phno)
    {
        return phonepeService.getByPhno(phno);
    }
    @GetMapping("/transactionbyacno")
    public List<Transaction> getAllTransactionByAccount(@RequestParam long transactionId)
    {
        return phonepeService.getByTransactionid(transactionId);
    }
    @GetMapping("/checkBalance")
    public Double getBalance()
    {
        return phonepeService.getBalance();
    }
    @GetMapping("/profile")
    public PhonepeUser profile()
    {
        return phonepeService.getUserByPhno();
    }
}
