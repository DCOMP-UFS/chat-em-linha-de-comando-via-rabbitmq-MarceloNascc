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
  ChatProto.Mensagem.Builder senderMessage = ChatProto.Mensagem.newBuilder();
  ChatProto.Conteudo.Builder content = ChatProto.Conteudo.newBuilder();

  // Building TimeZone Date
  Locale local = new Locale("pt", "BR");
  DateTimeFormatter dateformat = DateTimeFormatter.ofPattern("dd/MM/yyyy", local);
  DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm", local);

  // Setting senderMessage properties

  public void setMessage(String userName, String type, String userInput) throws IOException, TimeoutException {
    try {
      // Getting and formating date-time
      LocalDateTime dateNow = LocalDateTime.now(ZoneId.of("America/Sao_Paulo"));
      String messageDate = dateNow.format(dateformat);
      String messageTime = dateNow.format(timeFormat);

      byte[] userMessage = userInput.getBytes("UTF-8");

      // setting Content props
      content.setTipo("text/plain");
      content.setCorpo(ByteString.copyFrom(userMessage));

      // setting Message props
      senderMessage.setEmissor(userName);
      senderMessage.setData(messageDate);
      senderMessage.setHora(messageTime);
      senderMessage.setConteudo(content);

    } catch (Exception err) {
      System.out.println(err);
    }
  }

  public ChatProto.Mensagem getMessage() {
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
      ChatWith currentChatWith = new ChatWith();
      System.out.print("User: ");

      Scanner input = new Scanner(System.in);
      String userName = input.nextLine();

      ConnectionFactory factory = new ConnectionFactory();
      factory.setHost("100.25.35.237");
      factory.setUsername("admin");
      factory.setPassword("rabbitmq2022.2");
      factory.setVirtualHost("/");
      Connection connection = factory.newConnection();
      Channel senderChannel = connection.createChannel();
      Channel consumerChannel = connection.createChannel();

      Consumer consumer = new DefaultConsumer(consumerChannel) {
        public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body)
            throws IOException {
          ChatProto.Mensagem message = ChatProto.Mensagem.parseFrom(body);
          ChatProto.Conteudo content = message.getConteudo();

          String sender = message.getEmissor();
          String date = message.getData();
          String time = message.getHora();
          String bodyMessage = content.getCorpo().toStringUtf8();

          System.out.println("\n(" + date + " às " + time + ") " + sender + " diz: " + bodyMessage);
          System.out.print(currentChatWith.get() + ">> ");
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
            if (currentChatWith.get().length() > 0) {
              Message senderMessage = new Message();

              boolean isChattingWithAGroup = currentChatWith.get().startsWith("#");
              String name = !isChattingWithAGroup ? userName : userName + currentChatWith.get();

              senderMessage.setMessage(name, "text/plain", userInput);

              ChatProto.Mensagem message = senderMessage.getMessage();
              byte[] buffer = message.toByteArray();

              String exchange = isChattingWithAGroup ? currentChatWith.get().substring(1) : "";
              String routingKey = !isChattingWithAGroup ? currentChatWith.get().substring(1) : "";

              senderChannel.basicPublish(exchange, routingKey, null, buffer);
            } else {
              System.out.println("You must select who you want to chat with...");
            }
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