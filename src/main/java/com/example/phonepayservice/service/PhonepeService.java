package com.example.phonepayservice.service;

import com.example.phonepayservice.client.UserClient;
import com.example.phonepayservice.dto.User;
import com.example.phonepayservice.entity.LoginData;
import com.example.phonepayservice.entity.PhonepeUser;
import com.example.phonepayservice.entity.Transaction;
import com.example.phonepayservice.exception.BalanceException;
import com.example.phonepayservice.exception.UserNotExistException;
import com.example.phonepayservice.exception.UserNotRegisteredException;
import com.example.phonepayservice.repository.LoginDataRepository;
import com.example.phonepayservice.repository.PhonepeUserRepository;
import com.example.phonepayservice.repository.TransactionRepository;
import feign.FeignException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class PhonepeService {
    @Autowired
    private PhonepeUserRepository phonepeUserRepository;
    @Autowired
    private UserClient userClient;
    @Autowired
    private LoginDataRepository loginDataRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    private User user;
    public List<PhonepeUser> findAll() {
        return phonepeUserRepository.findAll();
    }
    public void login(long phno) {
        String x=""+phno;
        if(x.length()!=10||!x.matches("^[6-9].*"))
        {
            throw new UserNotExistException("Invalid mobile number");
        }
        User user=userClient.getUserByPhno(phno);
        if(user==null){
            throw new UserNotExistException("User not found");
        }
        this.user=user;
        LoginData loginData=new LoginData();
        loginData.setPhno(user.getPhno());
        loginDataRepository.save(loginData);

    }
    public PhonepeUser sendMoney(long phno,double amount){

        String x=""+phno;
        if(x.length()!=10||!x.matches("^[6-9].*"))
        {
            throw new UserNotExistException("Invalid mobile number");
        }
        List<LoginData> ll=loginDataRepository.findAll();
        if(!ll.isEmpty()) {
            user = userClient.getUserByPhno(ll.get(ll.size() - 1).getPhno());
        }
        if(user==null){
            throw new UserNotRegisteredException("Login to perform operation");
        }
        try {
            userClient.withdrawByphno(user.getPhno(), amount);
        }
        catch (FeignException e) {
            throw new BalanceException(e.contentUTF8());
        }
        if(userClient.getUserByPhno(phno)!=null) {
            userClient.depositByphno(phno, amount);
        }
        PhonepeUser phonepeUser=new PhonepeUser();
        System.out.println(user.getPhno());
        phonepeUser.setPhno(user.getPhno() );
        List<PhonepeUser> l=phonepeUserRepository.findAll();
        if(l.size()==0){
            phonepeUser.setTransactionid(100000);
        }
        else
        {
            phonepeUser.setTransactionid(l.get(l.size()-1).getTransactionid()+1);
        }
        Date date=new Date();
        phonepeUser.setDate(date);
        Transaction t=new Transaction();
        t.setPhno(user.getPhno());
        t.setAmount(amount);
        t.setMode("Transfer");
        t.setRecieverno(phno);
        t.setTransactionId(phonepeUser.getTransactionid());
        transactionRepository.save(t);
        return phonepeUserRepository.save(phonepeUser);
    }
    public PhonepeUser makePayment(double amount){
        List<LoginData> ll=loginDataRepository.findAll();
        if(!ll.isEmpty()) {
            user = userClient.getUserByPhno(ll.get(ll.size() - 1).getPhno());
        }
        if(user==null){
            throw new UserNotRegisteredException("Login to perform operation");
        }
        try {
            userClient.withdrawByphno(user.getPhno(), amount);
        }
        catch(FeignException e){
            throw new BalanceException(e.contentUTF8());
        }
        PhonepeUser phonepeUser=new PhonepeUser();
        System.out.println(user.getPhno());
        phonepeUser.setPhno(user.getPhno());
        List<PhonepeUser> l=phonepeUserRepository.findAll();
        if(l.size()==0){
            phonepeUser.setTransactionid(100000);
        }
        else
        {
            phonepeUser.setTransactionid(l.get(l.size()-1).getTransactionid()+1);
        }
        Date date=new Date();
        phonepeUser.setDate(date);
        Transaction t=new Transaction();
        t.setPhno(user.getPhno());
        t.setAmount(amount);
        t.setMode("Payment");
        t.setTransactionId(phonepeUser.getTransactionid());
        transactionRepository.save(t);
        return phonepeUserRepository.save(phonepeUser);
    }
    public List<Transaction> getAllTransaction()
    {
        return transactionRepository.findAll();
    }
    public List<Transaction>getByPhno(long phno)
    {
        return transactionRepository.findByphno(phno);
    }
    public List<Transaction> getByTransactionid(long transactionid)
    {
        return transactionRepository.findBytransactionId(transactionid);
    }
    public double getBalance()
    {
        List<LoginData> ll=loginDataRepository.findAll();
        if(!ll.isEmpty()) {
            user = userClient.getUserByPhno(ll.get(ll.size() - 1).getPhno());
        }
        if(user==null){
            throw new UserNotRegisteredException("Login to perform operation");
        }
        return  userClient.getUserByPhno(user.getPhno()).getBalance();
    }
    public PhonepeUser getUserByPhno()
    {
        List<LoginData> ll=loginDataRepository.findAll();
        if(!ll.isEmpty()) {
            user = userClient.getUserByPhno(ll.get(ll.size() - 1).getPhno());
        }
        if(user==null){
            throw new UserNotRegisteredException("Login to perform operation");
        }
        return phonepeUserRepository.findByphno(user.getPhno());
    }
}
