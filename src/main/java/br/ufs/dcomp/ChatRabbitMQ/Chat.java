package br.ufs.dcomp.ChatRabbitMQ;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.io.FileOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Scanner;
import java.time.ZoneId;
import java.util.concurrent.TimeoutException;
import java.util.Locale;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.rabbitmq.client.*;
import com.google.protobuf.*;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.json.JSONArray;
import org.json.JSONObject;

class Message {
  ChatProto.Message.Builder senderMessage = ChatProto.Message.newBuilder();
  ChatProto.Content.Builder content = ChatProto.Content.newBuilder();

  // Building TimeZone Date
  Locale local = new Locale("pt", "BR");
  DateTimeFormatter dateformat = DateTimeFormatter.ofPattern("dd/MM/yyyy", local);
  DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm", local);

  // Setting senderMessage properties

  public void setMessage(String userName, String groupName, String type, String userInput, Boolean isGroup,
      Boolean isFile) throws IOException, TimeoutException {
    try {
      // Getting and formating date-time
      LocalDateTime dateNow = LocalDateTime.now(ZoneId.of("America/Sao_Paulo"));
      String messageDate = dateNow.format(dateformat);
      String messageTime = dateNow.format(timeFormat);

      content.setType(type);

      if (isFile) {
        Path path = Paths.get(userInput);
        byte[] fileConvertedToByte = Files.readAllBytes(path);
        String fileNameSplitted[] = userInput.split("/");
        content.setName(fileNameSplitted[fileNameSplitted.length - 1]);
        content.setBody(ByteString.copyFrom(fileConvertedToByte));
      } else {
        byte[] userMessage = userInput.getBytes("UTF-8");
        content.setBody(ByteString.copyFrom(userMessage));
      }

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
  public void sendMessage(String userName, String userInput, ChatWith currentChatWith, Channel senderChannel,
      Boolean isFile) throws IOException, TimeoutException {
    try {
      if (currentChatWith.get().length() > 0) {
        Message senderMessage = new Message();

        boolean isChattingWithAGroup = currentChatWith.get().startsWith("#");
        String group = isChattingWithAGroup ? currentChatWith.get() : "";

        if (!isFile) {
          senderMessage.setMessage(userName, group, "message", userInput, isChattingWithAGroup, isFile);
        } else {
          Path source = Paths.get(userInput);
          String mimeType = Files.probeContentType(source);
          if (mimeType != null) {
            if (Files.exists(source)) {
              senderMessage.setMessage(userName, group, mimeType, userInput, isChattingWithAGroup, isFile);
            } else {
              System.out.println("This file doesn't exist, send a valid file path");
              System.out.print(currentChatWith.get() + ">> ");
              return;
            }
          } else {
            System.out.println("Send a valid file");
            System.out.print(currentChatWith.get() + ">> ");
            return;
          }
        }

        ChatProto.Message message = senderMessage.getMessage();
        byte[] buffer = message.toByteArray();

        String exchange = isChattingWithAGroup ? currentChatWith.get().substring(1) : "";
        String routingKey = !isChattingWithAGroup ? currentChatWith.get().substring(1) : "";

        senderChannel.basicPublish(exchange, routingKey, null, buffer);
      } else {
        System.out.println("You must select who you want to chat with...");
      }
    } catch (Exception e) {
      System.out.println(e);
    }
  }

  public void receiveMessage(byte[] body, ChatWith currentChatWith) throws IOException {
    ChatProto.Message message = ChatProto.Message.parseFrom(body);
    ChatProto.Content content = message.getContent();

    String sender = message.getSender();
    String date = message.getDate();
    String time = message.getTime();
    String group = message.getGroup();
    String contentType = content.getType();

    switch (contentType) {
      case "message":
        String bodyMessage = content.getBody().toStringUtf8();
        System.out.println("\n(" + date + " at " + time + ") " + sender + group + " says: " + bodyMessage);
        System.out.print(currentChatWith.get() + ">> ");
        break;
      default:
        String contentName = content.getName();
        ReceiveFile receiveFileThread = new ReceiveFile(content.getBody().toByteArray(), contentName, currentChatWith,
            sender, group, date, time);
        new Thread(receiveFileThread).start();
        break;
    }
  }
}

class ReceiveFile implements Runnable {
  private byte[] fileBody;
  private ChatWith currentChatWith;
  private String fileName;
  private String sender;
  private String group;
  private String date;
  private String time;

  public ReceiveFile(byte[] fileBody, String fileName, ChatWith currentChatWith, String sender, String group,
      String date, String time) {
    this.fileBody = fileBody;
    this.currentChatWith = currentChatWith;
    this.fileName = fileName;
    this.sender = sender;
    this.group = group;
    this.date = date;
    this.time = time;
  }

  @Override
  public void run() {
    try {
      FileOutputStream fos = new FileOutputStream("download/" + fileName);
      fos.write(fileBody);
      fos.close();
      System.out.println(
          "\n(" + date + " at " + time + ") file " + "\"" + fileName + "\"" + " received from " + sender + group + "!");
      System.out.print(currentChatWith.get() + ">> ");
    } catch (Exception e) {
      System.out.println("Something got wrong!");
      System.out.println(e);
    }
  }
}

class SendFile implements Runnable {
  private MessageRabbitMQ messageRabbitMQ = new MessageRabbitMQ();
  private String path;
  private String sender;
  private Channel senderChannel;
  private ChatWith currentChatWith;

  public SendFile(String sender, String path, ChatWith currentChatWith, Channel senderChannel) {
    this.sender = sender;
    this.path = path;
    this.currentChatWith = currentChatWith;
    this.senderChannel = senderChannel;
  }

  @Override
  public void run() {
    try {
      messageRabbitMQ.sendMessage(sender, path, currentChatWith, senderChannel, true);
    } catch (Exception e) {
      System.out.println("Something got wrong!");
      System.out.println(e);
    }
  }
}

class Commands {
  private Channel commandsChannel;
  private ChatWith currentChatWith;
  private String userName;
  private WebTarget webTarget;
  private String authorizationHeaderName;
  private String authorizationHeaderValue;

  public Commands(Connection conn, String currentUser, ChatWith currentChatWith, WebTarget target, String basicAuth)
      throws IOException {
    this.commandsChannel = conn.createChannel();
    this.userName = currentUser;
    this.currentChatWith = currentChatWith;
    this.webTarget = target;
    this.authorizationHeaderName = "Authorization";
    this.authorizationHeaderValue = "Basic "
        + java.util.Base64.getEncoder().encodeToString(basicAuth.getBytes());
  }

  private void printCommands() {
    System.out.println("Possible commands");
    System.out.println("addGroup: create a new group\nEx: !addGroup groupName\n");
    System.out.println("addUser: add a user in a group\nEX: !addUser userName groupName\n");
    System.out.println("delFromGroup: remove a user from a group\nEx: !delFromGroup userName groupName\n");
    System.out.println("removeGroup: delete a group\nEx: !removeGroup groupName\n");
    System.out.println("upload: uploads a file to a group or person while is talking with it\nEx: !upload path\n");
    System.out.println("listUsers: list users from a group\nEx: !listUsers groupName\n");
    System.out.println("listGroups: list all groups from a user\nEx: !listGroups userName\n");
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

  private void uploadFile(String path) throws IOException {
    SendFile fileThread = new SendFile(userName, path, currentChatWith, commandsChannel);
    new Thread(fileThread).start();
  }

  private void listUsers(String group) {
    Response response = this.webTarget
        .path("/exchanges/%2f/" + group + "/bindings/source")
        .request(MediaType.APPLICATION_JSON)
        .header(authorizationHeaderName, authorizationHeaderValue)
        .get();

    String users = "";
    if (response.getStatus() == 200) {
      String json = response.readEntity(String.class);
      JSONArray array = new JSONArray(json);

      for (int i = 0; i < array.length(); i++) {
        JSONObject obj = new JSONObject(array.get(i).toString());
        String value = obj.get("destination").toString();
        if (!value.isBlank()) {
          users += ", " + value;
        }
      }

      System.out.println(users.substring(2));
    } else {
      System.out.println("There's an error with the command listUsers: " + response.getStatus());
    }
  }

  private void listGroups() {
    Response response = this.webTarget
        .path("/queues/%2f/" + this.userName + "/bindings")
        .request(MediaType.APPLICATION_JSON)
        .header(authorizationHeaderName, authorizationHeaderValue)
        .get();

    String groups = "";
    if (response.getStatus() == 200) {
      String json = response.readEntity(String.class);
      JSONArray array = new JSONArray(json);

      for (int i = 0; i < array.length(); i++) {
        JSONObject obj = new JSONObject(array.get(i).toString());
        String value = obj.get("source").toString();
        if (!value.isBlank()) {
          groups += ", " + value;
        }
      }

      System.out.println(groups.substring(2));
    } else {
      System.out.println("There's an error with the command listGroups: " + response.getStatus());
    }
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
      case "!upload":
        if (currentChatWith.get().isEmpty()) {
          System.out.println("You must select who you want to send the file...");
          break;
        }
        if (this.hasCorrectAmountOfArgs(args, 2)) {
          this.uploadFile(args[1]);
        }
        break;
      case "!listUsers":
        if (this.hasCorrectAmountOfArgs(args, 2)) {
          this.listUsers(args[1]);
        }
        break;
      case "!listGroups":
        if (this.hasCorrectAmountOfArgs(args, 1)) {
          this.listGroups();
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
      factory.setHost("rabbitmq-sd-20222-lb-0c36c1cf9b9311ed.elb.us-east-1.amazonaws.com");
      factory.setUsername("admin");
      factory.setPassword("rabbitmq2022.2");
      factory.setVirtualHost("/");
      Connection connection = factory.newConnection();
      Channel senderChannel = connection.createChannel();
      Channel consumerChannel = connection.createChannel();

      Consumer consumer = new DefaultConsumer(consumerChannel) {
        public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body)
            throws IOException {
          messageRabbitMQ.receiveMessage(body, currentChatWith);
        }
      };

      senderChannel.queueDeclare(userName, false, false, false, null);
      consumerChannel.basicConsume(userName, true, consumer);

      WebTarget webTarget = ClientBuilder
          .newClient()
          .target("http://rabbitmq-sd-20222-lb-0c36c1cf9b9311ed.elb.us-east-1.amazonaws.com/api");
      Commands commandsExecutor = new Commands(
          connection,
          userName,
          currentChatWith,
          webTarget,
          "admin:rabbitmq2022.2");

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
            messageRabbitMQ.sendMessage(userName, userInput, currentChatWith, senderChannel, false);
            break;
        }

        System.out.print(currentChatWith.get() + ">> ");
        userInput = input.nextLine();
      }

      input.close();

      System.out.println("Leaving App...");
      senderChannel.close();
      consumerChannel.close();
      connection.close();
    } catch (Exception e) {
      System.out.println(e);
      System.out.println("1 - Verify if the host is correct\n2 - Verify if your user and password are correct");
    }
  }
}