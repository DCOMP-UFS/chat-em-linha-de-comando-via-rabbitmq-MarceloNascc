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

  public Commands(Connection conn) throws IOException {
    this.commandsChannel = conn.createChannel();
  }

  private void createGroup(String groupName) throws IOException {
    this.commandsChannel.exchangeDeclare(groupName, "fanout", false, false, null);
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
        this.createGroup(args[1]);
        break;

      case "!addUser":
        this.addUserToGroup(args[1], args[2]);
        break;

      case "!delFromGroup":
        this.removeUserFromGroup(args[1], args[2]);
        break;

      case "!removeGroup":
        this.deleteGroup(args[1]);
        break;

      default:
        System.out.println("Command unknown.");
        break;
    }
  }
}

public class Chat {
  public static void main(String[] argv) throws IOException, TimeoutException {
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

      Commands commandsExecutor = new Commands(connection);

      System.out.print("\n>> ");
      String userInput = input.nextLine();

      while (!userInput.contentEquals("exit")) {
        if (userInput.startsWith("@")) {
          currentChatWith.set(userInput);
          senderChannel.queueDeclare(currentChatWith.get().substring(1), false, false, false, null);
        } else if (userInput.startsWith("!")) {
          commandsExecutor.executeCommand(userInput);
        } else if (currentChatWith.get().length() > 0) {
          Message senderMessage = new Message();
          senderMessage.setMessage(userName.substring(0), "text/plain", userInput);

          ChatProto.Mensagem message = senderMessage.getMessage();
          byte[] buffer = message.toByteArray();

          senderChannel.basicPublish("", currentChatWith.get().substring(1), null, buffer);
        } else {
          System.out.println("You must select who you want to chat with...");
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