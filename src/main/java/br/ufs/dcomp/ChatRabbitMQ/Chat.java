package br.ufs.dcomp.ChatRabbitMQ;

import java.io.IOException;
import java.util.Scanner;
import java.time.ZoneId;
import java.util.concurrent.TimeoutException;
import java.util.Locale;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.rabbitmq.client.*;
import com.google.protobuf.*;

class Message {
  ChatProto.Message.Builder senderMessage = ChatProto.Message.newBuilder();
  ChatProto.Content.Builder content = ChatProto.Content.newBuilder();

  // Building TimeZone Date
  Locale local = new Locale("pt", "BR");
  DateTimeFormatter dateformat = DateTimeFormatter.ofPattern("dd/MM/yyyy", local);
  DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm", local);

  // Setting senderMessage properties

  public void setMessage(String userName, String groupName, String type, String userInput, Boolean isGroup) throws IOException, TimeoutException {
    try {
      // Getting and formating date-time
      LocalDateTime dateNow = LocalDateTime.now(ZoneId.of("America/Sao_Paulo"));
      String messageDate = dateNow.format(dateformat);
      String messageTime = dateNow.format(timeFormat);

      byte[] userMessage = userInput.getBytes("UTF-8");

      // setting Content props
      content.setType("text/plain");
      content.setBody(ByteString.copyFrom(userMessage));

      // setting Message props
      senderMessage.setSender(userName);
      senderMessage.setGroup(groupName);
      senderMessage.setDate(messageDate);
      senderMessage.setTime(messageTime);
      senderMessage.setContent(content);

    } catch (Exception err) {
      System.out.println(err);
    }
  }

  public ChatProto.Message getMessage() {
    return this.senderMessage.build();
  }
}

class ChatWith {
  private String user = "";

  public String get() {
    return user;
  }

  public void set(String name) {
    this.user = name;
  }
}

class MessageRabbitMQ {
  public void sendMessage (String userName, String userInput, ChatWith currentChatWith, Channel senderChannel) throws IOException, TimeoutException {
    if (currentChatWith.get().length() > 0) {
      Message senderMessage = new Message();
  
      boolean isChattingWithAGroup = currentChatWith.get().startsWith("#");
      String group = isChattingWithAGroup ? currentChatWith.get() : "";
  
      senderMessage.setMessage(userName, group, "text/plain", userInput, isChattingWithAGroup);
  
      ChatProto.Message message = senderMessage.getMessage();
      byte[] buffer = message.toByteArray();
  
      String exchange = isChattingWithAGroup ? currentChatWith.get().substring(1) : "";
      String routingKey = !isChattingWithAGroup ? currentChatWith.get().substring(1) : "";
  
      senderChannel.basicPublish(exchange, routingKey, null, buffer);
    } else {
      System.out.println("You must select who you want to chat with...");
    }
  }
  
  public void receiveMessage (byte[] body, ChatWith currentChatWith) throws IOException {
    ChatProto.Message message = ChatProto.Message.parseFrom(body);
    ChatProto.Content content = message.getContent();

    String sender = message.getSender();
    String date = message.getDate();
    String time = message.getTime();
    String group = message.getGroup();
    String bodyMessage = content.getBody().toStringUtf8();

    System.out.println("\n(" + date + " às " + time + ") " + sender + group + " diz: " + bodyMessage);
    System.out.print(currentChatWith.get() + ">> ");
  }
}

class Commands {
  private Channel commandsChannel;
  private String userName;

  public Commands(Connection conn, String currentUser) throws IOException {
    this.commandsChannel = conn.createChannel();
    this.userName = currentUser;
  }

  private void printCommands() {
    System.out.println("Possible commands");
    System.out.println("addGroup: create a new group\nEx: !addGroup groupName\n");
    System.out.println("addUser: add a user in a group\nEX: !addUser userName groupName\n");
    System.out.println("delFromGroup: remove a user from a group\nEx: !delFromGroup userName groupName\n");
    System.out.println("removeGroup: delete a group\nEx: !removeGroup groupName\n");
  }

  private boolean hasCorrectAmountOfArgs(String[] args, int length) {
    if (args.length != length) {
      System.out.println("Incorret command.\n\n");
      this.printCommands();

      return false;
    }

    return true;
  }

  private void createGroup(String groupName) throws IOException {
    this.commandsChannel.exchangeDeclare(groupName, "fanout", false, false, null);
    this.commandsChannel.queueBind(this.userName, groupName, "");
  }

  private void addUserToGroup(String userName, String groupName) throws IOException {
    this.commandsChannel.queueBind(userName, groupName, "");
  }

  private void removeUserFromGroup(String userName, String groupName) throws IOException {
    this.commandsChannel.queueUnbind(userName, groupName, "");
  }

  private void deleteGroup(String groupName) throws IOException {
    this.commandsChannel.exchangeDelete(groupName);
  }

  public void executeCommand(String userInput) throws IOException {
    String[] args = userInput.split(" ");
    String command = args[0];

    switch (command) {
      case "!addGroup":
        if (this.hasCorrectAmountOfArgs(args, 2)) {
          this.createGroup(args[1]);
        }
        break;
      case "!addUser":
        if (this.hasCorrectAmountOfArgs(args, 3)) {
          this.addUserToGroup(args[1], args[2]);
        }
        break;
      case "!delFromGroup":
        if (this.hasCorrectAmountOfArgs(args, 3)) {
          this.removeUserFromGroup(args[1], args[2]);
        }
        break;
      case "!removeGroup":
        if (this.hasCorrectAmountOfArgs(args, 2)) {
          this.deleteGroup(args[1]);
        }
        break;
      default:
        System.out.println("Command unknown.\n\n");
        this.printCommands();
        break;
    }
  }
}

public class Chat {
  public static void main(String[] argv) {
    try {
      MessageRabbitMQ messageRabbitMQ = new MessageRabbitMQ();
      
      ChatWith currentChatWith = new ChatWith();
      System.out.print("User: ");

      Scanner input = new Scanner(System.in);
      String userName = input.nextLine();

      ConnectionFactory factory = new ConnectionFactory();
      factory.setHost("54.237.55.155");
      factory.setUsername("admin");
      factory.setPassword("admin");
      factory.setVirtualHost("/");
      Connection connection = factory.newConnection();
      Channel senderChannel = connection.createChannel();
      Channel consumerChannel = connection.createChannel();

      Consumer consumer = new DefaultConsumer(consumerChannel) {
        public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
          messageRabbitMQ.receiveMessage(body, currentChatWith);
        }
      };

      senderChannel.queueDeclare(userName, false, false, false, null);
      consumerChannel.basicConsume(userName, true, consumer);

      Commands commandsExecutor = new Commands(connection, userName);

      System.out.print("\n>> ");
      String userInput = input.nextLine();

      while (!userInput.contentEquals("exit")) {
        switch (userInput.charAt(0)) {
          case '@':
            currentChatWith.set(userInput);
            senderChannel.queueDeclare(currentChatWith.get().substring(1), false, false, false, null);
            break;
          case '#':
            currentChatWith.set(userInput);
            break;
          case '!':
            commandsExecutor.executeCommand(userInput);
            break;
          default:
            messageRabbitMQ.sendMessage(userName, userInput, currentChatWith, senderChannel);
            break;
        }

        System.out.print(currentChatWith.get() + ">> ");
        userInput = input.nextLine();
      }

      input.close();

      System.out.println("Saindo do App...");
      senderChannel.close();
      consumerChannel.close();
      connection.close();
    } catch (Exception e) {
      System.out.println(e);
      System.out.println("1 - Verifique se o host está correto\n2 - Verifique se o usuário e senha estão corretos");
    }
  }
}