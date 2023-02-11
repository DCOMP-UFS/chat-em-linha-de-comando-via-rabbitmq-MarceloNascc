package br.ufs.dcomp.ChatRabbitMQ;

import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.TimeoutException;
import java.util.Locale;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.rabbitmq.client.*;

class ChatWith {
  private String user = "";
  
  public String get () {
    return user;
  }
  
  public void set (String name) {
    this.user = name;
  }
}

public class Chat {
  public static void main(String[] argv) throws IOException, TimeoutException {
    ChatWith currentChatWith = new ChatWith();
    System.out.print("User: ");

    Scanner input = new Scanner(System.in);
    String userName = input.nextLine();

    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost("54.83.166.117");
    factory.setUsername("admin");
    factory.setPassword("admin");
    factory.setVirtualHost("/");
    Connection connection = factory.newConnection();
    Channel senderChannel = connection.createChannel();
    Channel consumerChannel = connection.createChannel();

    Consumer consumer = new DefaultConsumer(consumerChannel) {
      public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
        String[] message = new String(body, "UTF-8").split(":::");
        Locale local = new Locale("pt", "BR");
        LocalDateTime sentAt = LocalDateTime.parse(message[0]);
        DateTimeFormatter dateformat = DateTimeFormatter.ofPattern("dd-MM-yyyy", local);
        DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm", local);
        String messageDate = sentAt.format(dateformat);
        String messageTime = sentAt.format(timeFormat);
        System.out.println("\n(" + messageDate + " às " + messageTime + ") " + message[1] + " diz: " + message[2]);
        if (currentChatWith.get().length() > 0) {
          System.out.print(currentChatWith.get() + ">> ");
        } else {
          System.out.print(">> ");
        }

      }
    };
    
    senderChannel.queueDeclare(userName, false, false, false, null);
    consumerChannel.basicConsume(userName, true, consumer);

    System.out.print("\n>> ");
    String userInput = input.nextLine();

    while (!userInput.contentEquals("exit")) {
      if (userInput.startsWith("@")) {
        currentChatWith.set(userInput);
        senderChannel.queueDeclare(currentChatWith.get().substring(1), false, false, false, null);
      } else if (currentChatWith.get().length() > 0) {
        LocalDateTime dateNow = LocalDateTime.now();
        String message = dateNow + ":::" + userName.substring(0) + ":::" + userInput;
        senderChannel.basicPublish("", currentChatWith.get().substring(1), null, message.getBytes("UTF-8"));
      } else {
        System.out.println("You must select who you want to chat with...");
      }

      System.out.print(currentChatWith.get() + ">> ");
      userInput = input.nextLine();
    }

    input.close();
    
    System.out.print("Saindo do App...");
    senderChannel.close();
    consumerChannel.close();
    connection.close();
  }
}