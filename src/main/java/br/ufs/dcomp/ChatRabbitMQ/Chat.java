package br.ufs.dcomp.ChatRabbitMQ;

import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.TimeoutException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.rabbitmq.client.*;

public class Chat {
  public static void main(String[] argv) throws IOException, TimeoutException {
    String currentChatWith = "";
    System.out.print("User: ");

    Scanner input = new Scanner(System.in);
    String userName = input.nextLine();

    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost("54.237.84.182");
    factory.setUsername("admin");
    factory.setPassword("rabbitmq2022.2");
    factory.setVirtualHost("/");
    Connection connection = factory.newConnection();
    Channel senderChannel = connection.createChannel();
    Channel consumerChannel = connection.createChannel();

    Consumer consumer = new DefaultConsumer(consumerChannel) {
      public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
        String[] message = new String(body, "UTF-8").split(":::");
        LocalDateTime sentAt = LocalDateTime.parse(message[0]);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy às HH:mm:ss");
        String messageSentAt = sentAt.format(formatter);
        System.out.print("\033[1F\33[K");
        System.out.println("\n(" + messageSentAt + ") " + message[1] + " diz: " + message[2]);
      }
    };
    senderChannel.queueDeclare(userName, false, false, false, null);
    consumerChannel.basicConsume(userName, true, consumer);

    System.out.print(">> ");
    String userInput = input.nextLine();

    while (!userInput.contentEquals("exit")) {
      if (userInput.startsWith("@")) {
        currentChatWith = userInput;
        senderChannel.queueDeclare(currentChatWith.substring(1), false, false, false, null);
      } else if (currentChatWith.length() > 0) {
        LocalDateTime dateNow = LocalDateTime.now();
        String message = dateNow + ":::" + currentChatWith.substring(1) + ":::" + userInput;
        senderChannel.basicPublish("", currentChatWith.substring(1), null, message.getBytes("UTF-8"));
      } else {
        System.out.println("You must select who you want to chat with...");
      }

      System.out.print(currentChatWith + ">> ");
      userInput = input.nextLine();
    }

    input.close();
  }
}