// $Id: JMailServer.java,v 1.26 2003/10/06 06:40:44 tsm20 Exp $

/**
 * Extra features:
 ******************************
 * Server can handle concurrent connections by utilising Threads.
 *
 * The server can be started in debug mode:
 *  $ java JMailServer debug
 * In this mode, the server will output extra information regarding
 * connections to the server
 *
 * Default users are:
 *  o tim:foobar
 *  o bob:foobar
 *  o joe:foobar
 *
 * POP3 Features:
 ********************
 * TOP <n> <m>
 * STAT
 *
 * If user doesn't not exist, user cannot login
 *
 * SMTP Features:
 ********************
 * SMTP messages are put in a queue and then every QUEUE_INTERVAL millisecs,
 * the queue is processed.
 *
 * File I/O:
 *********************
 * Can be persistent and all info is loaded on startup
 * Info is saved on change
 *
 * Admin Input on server: commands can be entered into the server console
 *   [ Help can be obtained by typing HELP or ? ]
 *
 */


import java.io.*;
import java.net.*;
import java.util.*;


/** A Java Implementation of a POP3 and SMTP server
 * @author tsm20 Tim Carey-Smith
 */
public class JMailServer {

    /** This is the path to the JMail config file */
    public static final String CONFIG_FILE = "JMail.cfg";

    /** This is the path to the JMail config file */
    public static final String USERLIST_FILE = "JMailUsers.cfg";

    /** This is the file extension used for storing serialized JMailUser objects */
    public static final String USER_FILEEXT = ".jmusr";

    /** This is the minimium value for QUEUE_INTERVAL (millisecs) */
    public static final int QUEUE_INTERVAL_MIN = 1000;

    // These variables are got from the config file [if it exists],
    // if they are not in the config file, the default values are used

    /** This is the port on which to listen for POP3 connections */
    public static int POP3_PORT = 2110;

    /** This is the port on which to listen for SMTP connections */
    public static int SMTP_PORT = 2025;

    /** This is the time interval (millisecs) between SMTPQueue processing */
    public static int QUEUE_INTERVAL = 3000;

    /** This is the regex which the domain should match if it is network-local */
    public static String NETWORK_LOCAL_REGEX = "^\\w+\\.cosc\\..*";

    /** This is the hostname of the SMTP forwarder */
    public static String SMTP_FORWARD_HOST = "mailhost.cosc.canterbury.ac.nz";

    /** This is the port of the SMTP forwarder */
    public static int SMTP_FORWARD_PORT = 25;

    /** Whether the server is in "debug" mode */
    private static boolean debugMode = false;

    /** Hostname associated with the server */
    public static String myHostname = null;

    /** Whether the server is currently online */
    public static boolean isOnline = true;

    /** Stores all users and messages relating to those users */
    public static JMailUserStore allUsers = null;

    /** Allows access to the config file and serialized JMailUsers */
    public static JMailFileIO fileio = null;

    /** Stores the current SMTP messages waiting for dequeuing */
    public static JMailSMTPQueue mySMTPQueue = null;

    /** Stores whether SMTPQueue is currently being processed */
    public static boolean isProcessingQueue = false;

    /** This is the Timer controlling the SMTPQueue processing */
    private static Timer mySMTPQueueTimer = null;

    private static JMailServerThread POP3Thread = null;
    private static JMailServerThread SMTPThread = null;

    /** This is the main method which starts the JMailServer
     *  and spawns the ServerThreads
     * @param args If the first argument is 'debug'; the server will enter debug mode in which more
     * detailed information is outputed  to the screen.
     * @throws IOException This method spawns Threads and therefore can throw IOExceptions
     */
    public static void main(String[] args) throws IOException {
        System.err.println("Server: Starting up....");

        initialisation(args);

        // Spawn thread for POP3 server
        POP3Thread = new JMailServerThread(POP3_PORT);
        POP3Thread.start();

        // Spawn thread for SMTP server
        SMTPThread = new JMailServerThread(SMTP_PORT);
        SMTPThread.start();

        adminReadIn();
    }

    /** Reads strings from System.in
     */
    private static void adminReadIn() {
        InputStreamReader readB;
        LineNumberReader reader;

        try {
            readB = new InputStreamReader(System.in);
        } catch (Exception e) {
            System.err.println("AdminRead: Cannot read from System.in");
            return;
        }

        try {
            reader = new LineNumberReader(readB);
            String line;

            while ((line = reader.readLine()) != null) {
                processAdminLine(line);
            }

            readB.close();
        } catch (Exception e) {
            System.err.println("AdminRead: Read Error");
        }
    }

    /** This method processes a line from the Admin
     * @param line the line entered by the Admin
     */
    private static void processAdminLine(String line) {
        String[] args = line.split(" ", 2);

        String arg1 = args[0].toUpperCase();

        if (arg1.equals("HELP") || arg1.equals("?")) {
            System.out.println("Commands: ");
            System.out.println("? - this stuff");
            System.out.println("HELP - this stuff");
            System.out.println("INFO - current config");
            System.out.println("QUEUE - size of SMTPQueue");
            System.out.println("USER - show list of users");
            System.out.println("USER <name> - show info about <name>");
            System.out.println(
                    "RETR <name> <n> - print mail message <n> for <name>");
            System.out.println("REST <name> - reset user login state for <name>");
            System.out.println(
                    "ADD <name>:<pass> - add new user <name> with password <pass>");
            System.out.println("ONLINE - return whether server is online");
            System.out.println("ONLINE 0|1 - set whether server is online");
            return;
        } else if (arg1.equals("INFO")) {
            System.out.println(getConfig());
            return;
        } else if (arg1.equals("QUIT")) {
            System.out.println("Will quit");
            System.exit(1);
            return;
        } else if (arg1.equals("QUEUE")) {
            System.out.println(
                    "QUEUE: " + JMailServer.mySMTPQueue.getQueueLength()
                    + " message(s)");
            return;
        } else if (arg1.equals("USER")) {
            if (args.length == 2 && !args[1].equals("")) {
                // Print user info from second arg

                JMailUser curr = null;

                try {
                    curr = JMailServer.allUsers.getUser(args[1]);
                } catch (JMailUserNonExistantException e) {
                    System.out.println("USER: User non-existant");
                    return;
                }

                System.out.println("USER: ");
                System.out.println("name='" + curr.getName() + "'");
                System.out.println("pass='" + curr.getPass() + "'");
                System.out.println("messages=" + curr.getMessageCount());
                return;
            }

            // No argument so print user list
            System.out.println("USER List");

            Enumeration<JMailUser> enu = allUsers.getUsers();

            while (enu.hasMoreElements()) {
                JMailUser temp = enu.nextElement();

                System.out.println(
                        " o " + temp.getName() + ": " + temp.getMessageCount()
                        + " message(s)");
            }
            return;
        } else if (arg1.equals("RSET")) {
            if (args.length == 2 && !args[1].equals("")) {
                // Print user info from second arg

                JMailUser curr = null;

                try {
                    curr = JMailServer.allUsers.getUser(args[1]);
                } catch (JMailUserNonExistantException e) {
                    System.out.println("RSET: User non-existant");
                    return;
                }

                if (curr != null) {
                    curr.setInPOP3Session(false);
                    System.out.println("RSET: Reset user '" + args[1] + "'");
                    return;
                }
            }

            // No argument
            System.out.println("RSET: Need username");
            return;
        } else if (arg1.equals("ONLINE")) {
            if (args.length == 2 && !args[1].equals("")) {
                // Print set the online state based on second arg
                int val = -1;

                try {
                    val = Integer.parseInt(args[1]);
                } catch (Exception e) {
                    System.out.println("ONLINE: Arg invalid");
                }

                switch (val) {
                case 1:
                    JMailServer.isOnline = true;
                    break;

                case 0:
                    JMailServer.isOnline = false;
                    break;

                default:
                    System.out.println("ONLINE: Arg invalid");
                    return;
                }
            }
            System.out.println(
                    "ONLINE: Server is "
                            + ((JMailServer.isOnline) ? "online" : "offline"));
            return;
        } else if (arg1.equals("RETR") && args.length == 2
                && !args[1].equals("")) {
            String[] userno = args[1].split(" ", 2);

            if (userno.length == 2 && !userno[0].equals("")
                    && !userno[1].equals("")) {
                String username = userno[0];
                String messageno = userno[1];

                JMailUser currUser = null;

                try {
                    currUser = JMailServer.allUsers.getUser(username);
                } catch (JMailUserNonExistantException e) {
                    System.out.println(
                            "RETR: User [" + username + "] non-existant");
                    return;
                }

                int messageNumb = -1;

                try {
                    messageNumb = Integer.parseInt(messageno);
                } catch (Exception e) {
                    // Bad arg
                    System.out.println(
                            "RETR: User Message [" + messageno + "] invalid");
                    return;
                }

                String messBody = "";

                try {
                    messBody = currUser.getMessage(messageNumb - 1);
                } catch (JMailMessageNonExistantException e) {
                    System.out.println(
                            "RETR: User Message [" + messageNumb
                            + "] non-existant");
                    return;
                }

                System.out.println("RETR: " + username + ":" + messageNumb);
                System.out.println("----");
                System.out.println(messBody);
                System.out.println("----");
                System.out.println("MESSAGE-END");
                return;
            }
            System.out.println("RETR: <username>'" + args[1] + "' bad");
            return;
        } else if (arg1.equals("ADD") && args.length == 2 && !args[1].equals("")) {
            String[] userpass = args[1].split(":", 2);

            if (userpass.length == 2 && !userpass[0].equals("")
                    && !userpass[1].equals("")) {
                String username = userpass[0];
                String password = userpass[1];

                try {
                    allUsers.addUser(username, password);
                    System.out.println(
                            "ADD: User [" + username + ":" + password
                            + "] added");
                    return;
                } catch (JMailUserExistsException e) {
                    System.out.println("ADD: User [" + username + "] exists");
                    return;
                } catch (Exception e) {
                    System.out.println(
                            "ADD: User [" + username + ":" + password
                            + "] failed");
                    return;
                }
            }
            System.out.println(
                    "ADD: <username>:<password>; '" + args[1] + "' bad");
            return;
        }

        System.out.println("UNKNOWN: type HELP for help");
    }

    /** This method initilises the Server
     *  and adds some default users to the UserStore
     * @param args These are the args passed from the command-line
     */
    private static void initialisation(String[] args) {
        System.err.println("Server: Initilisation...");

        /* Check debug mode */
        if (args.length > 0 && args[0].equals("debug")) {
            System.err.println("Server: Debug Mode");
            debugMode = true;
        }

        // Get hostname/domain-name for server
        try {
            myHostname = InetAddress.getLocalHost().getCanonicalHostName();
        } catch (UnknownHostException e) {
            System.err.println(
                    "SMTPConnect: Couldn't retrieve Hostname; Using localhost");
            myHostname = "localhost";
        }

        System.err.println("Server: running on '" + myHostname + "'");

        // Setup thread and timer for SMTPQueue processing
        mySMTPQueue = new JMailSMTPQueue();
        mySMTPQueueTimer = new Timer(true); // New daemon timer
        mySMTPQueueTimer.schedule(new JMailSMTPQueueProcessTask(), 5,
                QUEUE_INTERVAL);

        // Make the user store
        allUsers = new JMailUserStore();

        // Load the config
        fileio = new JMailFileIO();
        fileio.loadConfigFile();
        fileio.loadUsers();

        printDebug(getConfig());
    }

    public static String getConfig() {
        String toRet = "Current Config\n";

        toRet += " o HostName=" + JMailServer.myHostname + "\n";
        toRet += " o POP3 Clients="
                + ((JMailServer.POP3Thread == null)
                        ? "offline"
                        : Integer.toString(
                                JMailServer.POP3Thread.getClientCount()))
                                + "\n";
        toRet += " o SMTP Clients="
                + ((JMailServer.SMTPThread == null)
                        ? "offline"
                        : Integer.toString(
                                JMailServer.SMTPThread.getClientCount()))
                                + "\n";
        toRet += " o POP3_PORT=" + JMailServer.POP3_PORT + "\n";
        toRet += " o SMTP_PORT=" + JMailServer.SMTP_PORT + "\n";
        toRet += " o QUEUE_INTERVAL=" + JMailServer.QUEUE_INTERVAL + "\n";
        toRet += " o NETWORK_LOCAL_REGEX=" + JMailServer.NETWORK_LOCAL_REGEX
                + "\n";
        toRet += " o SMTP_FORWARD_HOST=" + JMailServer.SMTP_FORWARD_HOST + "\n";
        toRet += " o SMTP_FORWARD_PORT=" + JMailServer.SMTP_FORWARD_PORT;

        return toRet;
    }

    /** This method prints out the [message],
     *  if the Server is in 'debugMode'
     * @param message the message to be printed
     */
    public static void printDebug(String message) {
        if (debugMode) {
            System.err.println(message);
        }
    }
}


/** This TimerTask processes the SMTPQueue every QUEUE_INTERVAL millisecs */
class JMailSMTPQueueProcessTask extends TimerTask {

    /** This method calls the processQueue method of the global SMTPQueue */
    public void run() {
        if (JMailServer.isProcessingQueue) {
            return;
        }

        JMailServer.isProcessingQueue = true;
        JMailServer.mySMTPQueue.processQueue();
        JMailServer.isProcessingQueue = false;
    }
}


/** This class is reponsible for all the servers File Input/Output */
class JMailFileIO {
    private String homeDir = null;

    public JMailFileIO() {
        homeDir = System.getProperty("user.home") + "/.jmail";

        File dir = new File(homeDir);

        if (dir.isDirectory()) {// It is OK
        } else {
            System.err.println("File I/O: Creating ~/.jmail");
            dir.mkdir();
        }
    }

    public void loadConfigFile() {
        FileReader fileR;
        LineNumberReader reader;

        try {
            fileR = new FileReader(homeDir + "/" + JMailServer.CONFIG_FILE);
        } catch (FileNotFoundException e) {
            System.err.println(
                    "File I/O: Config File not present; Using default values");
            return;
        }

        try {
            reader = new LineNumberReader(fileR);
            String line;

            while ((line = reader.readLine()) != null) {
                processConfigLine(line);
            }

            reader.close();
            fileR.close();
        } catch (Exception e) {
            System.err.println("File I/O: Config File Read Error");
        }
    }

    public void loadUsers() {
        FileReader fileR;
        LineNumberReader reader;

        try {
            fileR = new FileReader(homeDir + "/" + JMailServer.USERLIST_FILE);
        } catch (FileNotFoundException e) {
            System.err.println(
                    "File I/O: UserList file not present; add default users");

            addDefaultUsers();

            return;
        }

        try {
            reader = new LineNumberReader(fileR);
            String line;

            while ((line = reader.readLine()) != null) {
                if (!loadUser(line)) {
                    JMailServer.printDebug(
                            "File I/O: User load failed; add default users");

                    addDefaultUsers();

                    throw new Exception("Bad user");
                }
            }

            fileR.close();
        } catch (Exception e) {
            System.err.println("File I/O: User List Read Error");
        }
    }

    private void addDefaultUsers() {
        try {
            JMailServer.allUsers.addUser("tim", "foobar");
            JMailServer.allUsers.addUser("joe", "foobar");
            JMailServer.allUsers.addUser("phil", "foo");
            JMailServer.allUsers.addUser("nick", "foobar");
        } catch (JMailUserExistsException e) {
            System.err.println("File I/O: User exists");
        }
    }

    private void processConfigLine(String line) {
        if (line.startsWith("#")) {
            // Comment
            return;
        }

        if (line.indexOf('=') == -1) {
            // Not a useful line
            return;
        }

        String[] varval = line.split("=", 2);

        String var = varval[0];
        String val = varval[1];

        if (var == "" || val == "") {
            // Useless
            return;
        }

        // Now check the var
        if (var.equals("POP3_PORT")) {
            try {
                JMailServer.POP3_PORT = Integer.parseInt(val);
                return;
            } catch (Exception e) {
                // Bad arg
                return;
            }
        } else if (var.equals("SMTP_PORT")) {
            try {
                JMailServer.SMTP_PORT = Integer.parseInt(val);
                return;
            } catch (Exception e) {
                // Bad arg
                return;
            }
        } else if (var.equals("QUEUE_INTERVAL")) {
            try {
                int temp = Integer.parseInt(val);

                if (temp < JMailServer.QUEUE_INTERVAL_MIN) {
                    return;
                }
                JMailServer.QUEUE_INTERVAL = temp;
                return;
            } catch (Exception e) {
                // Bad arg
                return;
            }
        } else if (var.equals("NETWORK_LOCAL_REGEX")) {
            JMailServer.NETWORK_LOCAL_REGEX = val;
            return;
        } else if (var.equals("SMTP_FORWARD_HOST")) {
            JMailServer.SMTP_FORWARD_HOST = val;
            return;
        } else if (var.equals("SMTP_FORWARD_PORT")) {
            try {
                JMailServer.SMTP_FORWARD_PORT = Integer.parseInt(val);
                return;
            } catch (Exception e) {
                // Bad arg
                return;
            }
        }
    }

    private boolean loadUser(String username) {
        if (username.startsWith("#")) {
            // Comment
            return false;
        }

        try {
            FileInputStream fis = new FileInputStream(
                    homeDir + "/" + username + JMailServer.USER_FILEEXT);
            ObjectInputStream ois = new ObjectInputStream(fis);

            JMailUser temp = (JMailUser) ois.readObject();

            JMailServer.allUsers.addUserObject(temp);
            JMailServer.printDebug(
                    "File I/O: loadUser success [" + username + "]");

            ois.close();
            fis.close();
        } catch (Exception e) {
            System.err.println("File I/O: loadUser fail [" + username + "]");
            return false;
        }
        return true;
    }

    public boolean saveUser(String username) {
        JMailUser myUser = null;

        try {
            // User exists
            myUser = JMailServer.allUsers.getUser(username);
        } catch (JMailUserNonExistantException e) {
            return false;
        }

        try {
            FileOutputStream fos = new FileOutputStream(
                    homeDir + "/" + username + JMailServer.USER_FILEEXT);
            ObjectOutputStream oos = new ObjectOutputStream(fos);

            oos.writeObject(myUser);

            oos.flush();
            oos.close();
            fos.close();
        } catch (Exception e) {
            System.err.println("File I/O: saveUser fail [" + username + "]");
            return false;
        }

        JMailServer.printDebug("File I/O: saveUser success [" + username + "]");

        return true;
    }

    public boolean saveUserList() {
        FileOutputStream fos = null;
        PrintWriter printW = null;

        try {
            fos = new FileOutputStream(homeDir + "/" + JMailServer.USERLIST_FILE);
            printW = new PrintWriter(fos);
        } catch (FileNotFoundException e) {
            System.err.println("File I/O: saveUserList fail");
            return false;
        }

        Enumeration<JMailUser> enu = JMailServer.allUsers.getUsers();

        while (enu.hasMoreElements()) {
            JMailUser temp = enu.nextElement();

            printW.println(temp.getName());
        }

        try {
            printW.close();
            fos.close();
        } catch (IOException e) {
            return false;
        }

        JMailServer.printDebug("File I/O: saveUserList success");
        return true;
    }
}


class JMailThreadAccessDeniedException extends Exception {
    public static final long serialVersionUID = 1L;

    public JMailThreadAccessDeniedException(String message) {
        super(message);
    }
}


class JMailUserExistsException extends Exception {
    public static final long serialVersionUID = 1L;

    public JMailUserExistsException(String message) {
        super(message);
    }
}


class JMailUserNonExistantException extends Exception {
    public static final long serialVersionUID = 1L;

    public JMailUserNonExistantException(String message) {
        super(message);
    }
}


class JMailMessageNonExistantException extends Exception {
    public static final long serialVersionUID = 1L;

    public JMailMessageNonExistantException(String message) {
        super(message);
    }
}


/** This Thread creates a new ServerSocket and
 *  spawns a new Thread when a connection is accepted */
class JMailServerThread extends Thread {

    /** Port which the serer will listen on */
    private int myPort = -1;
    private int clientCount = 0;
    private Vector<Thread> children = new Vector<Thread>();

    /** Instantiates a JMailServerThread object
     * @param listenPort port to listen for connections on using a ServerSocket
     */
    public JMailServerThread(int listenPort) {
        super("JMailServerThread");
        this.myPort = listenPort;
    }

    public int getClientCount() {
        return clientCount;
    }

    public void decrecmentClientCount(Thread obj) throws JMailThreadAccessDeniedException {
        if (children.contains(obj)) {
            clientCount--;
            children.remove(obj);
            return;
        }

        // Object is not allowed
        throw new JMailThreadAccessDeniedException(
                "ServerThread: Invalid access");
    }

    /** This method is run when the Thread is started<br>
     * It start a ServerSocket and accepts connections
     */
    public void run() {
        ServerSocket serverSocket = null;

        try {
            serverSocket = new ServerSocket(myPort);
        } catch (IOException e) {
            System.err.println("ServerThread(" + myPort + "): Could not listen");
            return;
        }
        System.err.println("ServerThread(" + myPort + "): Listening");

        boolean keepSockets = true;

        // Infinite loop
        while (keepSockets) {
            try {
                if (myPort == JMailServer.POP3_PORT) {
                    Thread temp = new JMailServerPOP3Thread(
                            serverSocket.accept(), this);

                    children.add(temp);
                    temp.start();
                } else if (myPort == JMailServer.SMTP_PORT) {
                    Thread temp = new JMailServerSMTPThread(
                            serverSocket.accept(), this);

                    children.add(temp);
                    temp.start();
                }
                clientCount++;
            } catch (IOException e) {
                System.err.println(
                        "ServerThread(" + myPort
                        + "): Failed to accept connection");
            }
        }

        try {
            serverSocket.close();
        } catch (IOException e) {
            System.err.println(
                    "ServerThread(" + myPort
                    + "): Failed to close Server socket");
        }
        System.err.println("ServerThread(" + myPort + "): Shutting down server");
    }
}


/** This Thread is spawned when the server accepts a connection on POP3_PORT.
 *  It handles a single POP3 connection */
class JMailServerPOP3Thread extends Thread {

    /** Socket relating to the Connection */
    private Socket mySocket = null;
    private JMailServerThread myParent = null;

    public JMailServerPOP3Thread(Socket socket, JMailServerThread parent) {
        super("JMailServerThread");
        this.mySocket = socket;
        this.myParent = parent;
    }

    public void run() {
        PrintWriter out = null;
        BufferedReader in = null;

        System.err.println(
                "ServerPOP3: Accept succeeded to "
                        + mySocket.getInetAddress().getHostAddress() + ":"
                        + mySocket.getPort());

        try {
            out = new PrintWriter(mySocket.getOutputStream(), true);
            in = new BufferedReader(
                    new InputStreamReader(mySocket.getInputStream()));
        } catch (IOException e) {
            System.err.println("ServerPOP3: Failed to setup Writer/Reader");
            return;
        }

        String inputLine = null, outputLine = null;

        JMailPOP3Connection serverConnection = new JMailPOP3Connection(mySocket);

        boolean cleanShutdown = false;
        String hostString = "ServerPOP3 ["
                + mySocket.getInetAddress().getHostAddress() + ":"
                + mySocket.getPort() + "] ";

        outputLine = serverConnection.processInput(null);
        JMailServer.printDebug(hostString + "OUT<< " + outputLine);
        out.println(outputLine);

        try {
            while ((inputLine = in.readLine()) != null) {
                JMailServer.printDebug(hostString + " IN>> " + inputLine);
                outputLine = serverConnection.processInput(inputLine);
                JMailServer.printDebug(hostString + "OUT<< " + outputLine);
                out.println(outputLine);

                if (serverConnection.getCurrState()
                        == JMailPOP3Connection.STATE_END) {
                    cleanShutdown = true;
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("ServerPOP3: Failed to read/write to client");
        }

        if (!cleanShutdown) {
            // Clean up for terminated connections
            try {
                serverConnection.resetAllMessages();
            } catch (Exception e) {
                // This either means no user was logged on or connection was
                System.err.println("ServerPOP3: could not resetAllMessages()");
            }
        }

        try {
            out.close();
            in.close();
            mySocket.close();
        } catch (IOException e) {
            System.err.println("ServerPOP3: Failed to close client connection");
        }

        try {
            myParent.decrecmentClientCount(this);
        } catch (JMailThreadAccessDeniedException e) {
            System.err.println(
                    "ServerPOP3: Not allowed to decrement ServerThread count");
        }

        System.err.println(
                "ServerPOP3: Connection closed to "
                        + mySocket.getInetAddress().getHostAddress() + ":"
                        + mySocket.getPort());
    }
}


/** This Thread is spawned when the server accepts a connection on SMTP_PORT.
 *  It handles a single SMTP connection */
class JMailServerSMTPThread extends Thread {

    /** Socket relating to the Connection */
    private Socket mySocket = null;
    private JMailServerThread myParent = null;

    public JMailServerSMTPThread(Socket socket, JMailServerThread parent) {
        super("JMailServerThread");
        this.mySocket = socket;
        this.myParent = parent;
    }

    public void run() {
        PrintWriter out = null;
        LineNumberReader in = null;

        System.err.println(
                "ServerSMTP: Accept succeeded to "
                        + mySocket.getInetAddress().getHostAddress() + ":"
                        + mySocket.getPort());

        try {
            out = new PrintWriter(mySocket.getOutputStream(), true);
            in = new LineNumberReader(
                    new InputStreamReader(mySocket.getInputStream()));
        } catch (IOException e) {
            System.err.println("ServerSMTP: Failed to setup Writer/Reader");
            return;
        }

        String inputLine = null, outputLine = null;

        JMailSMTPConnection serverConnection = new JMailSMTPConnection(mySocket);

        String hostString = "ServerSMTP: ["
                + mySocket.getInetAddress().getHostAddress() + ":"
                + mySocket.getPort() + "] ";

        outputLine = serverConnection.processInput(null);
        JMailServer.printDebug(hostString + "OUT<< " + outputLine);
        out.println(outputLine);

        try {
            while ((inputLine = in.readLine()) != null) {
                JMailServer.printDebug(hostString + " IN>> " + inputLine);
                outputLine = serverConnection.processInput(inputLine);
                JMailServer.printDebug(hostString + "OUT<< " + outputLine);
                if (!(serverConnection.getCurrState()
                        == JMailSMTPConnection.STATE_DATA
                                && outputLine == null)) {
                    out.println(outputLine);
                }

                if (serverConnection.getCurrState()
                        == JMailSMTPConnection.STATE_END) {
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("ServerSMTP: Failed to read/write to client");
        }

        try {
            out.close();
            in.close();
            mySocket.close();
        } catch (IOException e) {
            System.err.println("ServerSMTP: Failed to close client connection");
        }

        try {
            myParent.decrecmentClientCount(this);
        } catch (JMailThreadAccessDeniedException e) {
            System.err.println(
                    "ServerSMTP: Not allowed to decrement ServerThread count");
        }

        System.err.println(
                "ServerSMTP: Connection closed to "
                        + mySocket.getInetAddress().getHostAddress() + ":"
                        + mySocket.getPort());
    }
}


/** This class is responsible for storing and delivering mail messages which
 *  are received on SMTP_PORT */
class JMailSMTPQueue {

    /** Vector of SMTP messages waiting to be dequeued */
    private Vector<JMailSMTPMessage> myQueue = null;

    public JMailSMTPQueue() {
        this.myQueue = new Vector<JMailSMTPMessage>();
    }

    /** This method adds a message to the SMTPQueue which will be forwarded to the correct location
     * @param clientName name of the Client which the SMTPMessage was received from
     * @param clientIP InetAddress of the
     * @param sender the email address of the sender
     * @param recipents Vector of email address to send the message to
     * @param body body of the SMTPMessage
     * @return true if successful<br>
     * false otherwise
     */
    public boolean addMessage(String HELOName, String clientName, InetAddress clientIP, JMailEmailCombo sender, JMailEmailComboList recipents, String body) {
        JMailSMTPMessage temp = new JMailSMTPMessage(HELOName, clientName,
                clientIP, sender, recipents, body);

        myQueue.add(temp);
        return true;
    }

    public int getQueueLength() {
        return myQueue.size();
    }

    /** This method is run every so often to process
     *  each message and deliver them correctly */
    public void processQueue() {
        Vector<JMailSMTPMessage> toRemove = new Vector<JMailSMTPMessage>();
        JMailSMTPMessage currMessage = null;
        JMailEmailCombo currRcpt = null;

        Enumeration<JMailSMTPMessage> messages = myQueue.elements();

        while (messages.hasMoreElements()) {
            currMessage = messages.nextElement();

            Enumeration<JMailEmailCombo> recipents = currMessage.getRecipents().elements();

            while (recipents.hasMoreElements()) {
                currRcpt = recipents.nextElement();

                String toSend = "Received: from " + currMessage.getHELOName()
                        + " (" + currMessage.getClientName() + " ["
                        + currMessage.getClientIP().getHostAddress() + "])\r\n"
                        + " by " + JMailServer.myHostname
                        + " (JMail SMTP Server)\r\n"
                        + " with SMTP id <KJFD7SD8FDSJ432FDS@"
                        + JMailServer.myHostname + ">\r\n" + " for "
                        + currRcpt.parsed() + "; " + currMessage.getDate()
                        + "\r\n" + currMessage.getBody() + "\r\n.";

                if (currRcpt.isDomainServerLocal()) {
                    // Local email
                    try {
                        JMailServer.allUsers.getUser(currRcpt.user());
                        System.out.println("SMTPQueue: ServerLocalDomain");
                        JMailServer.allUsers.getUser(currRcpt.user()).addMessage(
                                "Return Path: <"
                                        + currMessage.getSender().parsed()
                                        + ">\r\n" + toSend);
                        toRemove.add(currMessage);
                    } catch (JMailUserNonExistantException e) {// User doesn't exist
                    }
                } else {
                    // Remote email
                    boolean isLocal = currRcpt.isDomainNetworkLocal();

                    if (isLocal) {
                        // Host inside local subnet
                        // Forward to that machine:SMTP_PORT
                        System.out.println("SMTPQueue: NetworkLocalDomain");
                        if (sendMessage(currRcpt.domain(), JMailServer.SMTP_PORT,
                                currMessage.getSender(), currRcpt, toSend)) {
                            toRemove.add(currMessage);
                        }
                    } else {
                        // Host outside local subnet
                        // Forward to SMTP_FORWARD_HOST:SMTP_FORWARD_PORT
                        System.out.println("SMTPQueue: ExternalDomain");
                        if (sendMessage(JMailServer.SMTP_FORWARD_HOST,
                                JMailServer.SMTP_FORWARD_PORT,
                                currMessage.getSender(), currRcpt, toSend)) {
                            toRemove.add(currMessage);
                        }
                    }
                }
            }
        }

        Enumeration<JMailSMTPMessage> removing = toRemove.elements();

        while (removing.hasMoreElements()) {
            myQueue.remove(removing.nextElement());
        }
    }

    /** This method attempts to connect to a remote SMTP server and forward a mail message
     * @param hostName hostname of SMTP server to connect to
     * @param port port of SMTP server to connect to
     * @param sender send of the SMTP message
     * @param recipent recipient of the SMTP message
     * @param body body of the SMTP message
     * @return true if successful<br>
     * false otherwise
     */
    private boolean sendMessage(String hostName, int port, JMailEmailCombo sender, JMailEmailCombo recipent, String body) {
        Socket mySocket = null;
        PrintWriter out = null;
        BufferedReader in = null;

        try {
            mySocket = new Socket(hostName, port);
        } catch (UnknownHostException e) {
            System.err.println("SMTPConnect: Don't know about " + hostName);
            return false;
        } catch (IOException e) {
            System.err.println(
                    "SMTPConnect: Connect failed to " + hostName + ":" + port);
            return false;
        }

        System.err.println(
                "SMTPConnect: Connect succeeded to "
                        + mySocket.getInetAddress().getHostAddress() + ":"
                        + mySocket.getPort());

        try {
            out = new PrintWriter(mySocket.getOutputStream(), true);
            in = new BufferedReader(
                    new InputStreamReader(mySocket.getInputStream()));
        } catch (IOException e) {
            System.err.println("SMTPConnect: Failed to setup Writer/Reader");
            return false;
        }

        String inputLine = null, outputLine = null;

        JMailSMTPClientConnection clientConnection = new JMailSMTPClientConnection(
                mySocket, sender, recipent, body);

        String hostString = "SMTPConnect: ["
                + mySocket.getInetAddress().getHostAddress() + ":"
                + mySocket.getPort() + "] ";

        try {
            while ((inputLine = in.readLine()) != null) {
                JMailServer.printDebug(hostString + " IN>> " + inputLine);
                outputLine = clientConnection.processInput(inputLine);

                if (clientConnection.getCurrState()
                        == JMailSMTPClientConnection.STATE_END) {
                    break;
                }

                JMailServer.printDebug(hostString + "OUT<< " + outputLine);
                out.println(outputLine);
            }
        } catch (IOException e) {
            System.err.println("SMTPConnect: Failed to read/write to client");
        }

        try {
            out.close();
            in.close();
            mySocket.close();
        } catch (IOException e) {
            System.err.println("SMTPConnect: Failed to close server connection");
        }

        System.err.println(
                "SMTPConnect: Connection closed to "
                        + mySocket.getInetAddress().getHostAddress() + ":"
                        + mySocket.getPort());
        return true;
    }
}


//////////////////////////////////////////////////////////////////////

/** This Connection is responsible for handling the protocol of a POP3 connection.
 *  There is a unique Connection object for each POP3 connection. */
class JMailPOP3Connection {

    /** This state is when waiting for USER */
    public static final int STATE_AUTH_USER = 10;

    /** This state is when waiting for PASS */
    public static final int STATE_AUTH_PASS = 15;

    /** This state is when waiting for any query */
    public static final int STATE_TRANSACTION = 20;

    /** This state is when commiting all changes on clean shutdown */
    public static final int STATE_UPDATE = 30;

    /** This state is when QUIT cmd has been given */
    public static final int STATE_END = 3000;

    //////////////////////////////////////////////////

    /** This variable holds the Socket relating to the connection */
    private Socket relatedSocket = null;

    /** This variable holds the current state of the connection */
    private int currState = 0;

    /** username of currently logged in user */
    private String myUserName = null;

    /** JMailUser class relating to the username */
    private JMailUser myUser = null;

    public JMailPOP3Connection(Socket thatSocket) {
        this.currState = STATE_AUTH_USER;
        this.relatedSocket = thatSocket;
    }

    public int getCurrState() {
        return currState;
    }

    public String processInput(String theInput) {
        try {
            if (theInput.toUpperCase().split(" ")[0].equals("QUIT")) {
                if (currState == STATE_TRANSACTION) {
                    commitChanges();
                }

                currState = STATE_END;
                return "+OK Good for you; Smoking is bad";
            }
        } catch (Exception e) {// theInput doesn't contain QUIT
        }

        if (JMailServer.isOnline) {
            // Server is Online
            return processStates(theInput) + "\r";
        }

        // Server is offline
        return "-ERR Service Not Available\r";
    }

    /** This method processes the inputString.
     * Checking whether the protocol is being followed.
     * @param theInput string to parse
     * @return string message to return to client
     */
    private String processStates(String theInput) {
        if (theInput == null) {
            // Welcome message
            return "+OK JMail POP3 Server ready on " + JMailServer.myHostname;
        }

        // Something useful to parse
        String[] myArgs = theInput.split(" ", 2);
        String arg1 = myArgs[0].toUpperCase();

        // HELO
        if (arg1.equals("USER")) {
            if (currState == STATE_AUTH_USER || currState == STATE_AUTH_PASS) {
                if (myArgs.length >= 2 && !myArgs[1].equals("")) {
                    myUserName = myArgs[1];
                    currState = STATE_AUTH_PASS;
                    return "+OK Welcome " + myUserName + " pleased to meet you";
                }
                // Problem with number of args
                return "-ERR USER requires arg";
            }

            return "-ERR Not in AUTH State";
        } else if (arg1.equals("PASS")) {
            if (currState == STATE_AUTH_PASS) {
                if (myArgs.length >= 2 && !myArgs[1].equals("")) {
                    String testPass = myArgs[1];

                    try {
                        myUser = JMailServer.allUsers.getUser(myUserName);
                        if (checkPass(testPass)) {
                            currState = STATE_TRANSACTION;
                            if (myUser.isInPOP3Session()) {
                                currState = STATE_AUTH_USER;
                                return "-ERR " + myUserName
                                        + " already logged in; try again later";
                            }
                            myUser.setInPOP3Session(true);
                            return "+OK " + myUserName + " login success";
                        }
                    } catch (JMailUserNonExistantException e) {
                        return "-ERR " + myUserName + " login failure";
                    }
                }
                // Problem with number of args
                return "-ERR PASS requires arg";
            }
            return "-ERR Not in AUTH State";
        } // STAT
        else if (arg1.equals("STAT")) {
            try {
                return getSummaryDropListing();
            } catch (JMailMessageNonExistantException e) {
                return "-ERR STAT Error";
            }
        } // LIST
        else if (arg1.equals("LIST")) {
            if (currState == STATE_TRANSACTION) {
                if (myArgs.length >= 2 && !myArgs[1].equals("")) {
                    // An argument exists
                    int messageNo = -1;

                    try {
                        messageNo = Integer.parseInt(myArgs[1]);
                    } catch (Exception e) {
                        // Bad argument
                        messageNo = -1;
                    }

                    try {
                        // Get drop listing of specific message
                        return getDropListing(messageNo - 1);
                    } catch (JMailMessageNonExistantException e) {
                        return "-ERR LIST bad arg";
                    }
                }
                // No argument exists
                try {
                    return getDropListing();
                } catch (JMailMessageNonExistantException e) {
                    return "-ERR LIST Error";
                }
            }
            return "-ERR AUTH first";
        } // RETR
        else if (arg1.equals("RETR")) {
            if (currState == STATE_TRANSACTION) {
                if (myArgs.length >= 2 && !myArgs[1].equals("")) {
                    // An argument exists
                    int messageNo = -1;

                    try {
                        messageNo = Integer.parseInt(myArgs[1]);
                    } catch (Exception e) {
                        // Bad argument
                        messageNo = -1;
                    }

                    try {
                        if (myUser.getMessageState(messageNo - 1)
                                == JMailPOP3Message.STATE_NORMAL) {
                            // Get content of specific message
                            return "+OK ("
                                    + myUser.getMessageBody(messageNo - 1).length()
                                    + " octets)\r\n"
                                    + myUser.getMessageBody(messageNo - 1);
                        } else {
                            return "-ERR RETR message marked";
                        }
                    } catch (JMailMessageNonExistantException e) {
                        return "-ERR RETR bad arg";
                    }
                }
                // No argument exists
                return "-ERR RETR requires arg";
            }
            return "-ERR AUTH first";
        } // TOP
        else if (arg1.equals("TOP")) {
            if (currState == STATE_TRANSACTION) {
                if (myArgs.length >= 2 && !myArgs[1].equals("")) {
                    // An argument exists
                    String[] messsize = myArgs[1].split(" ", 2);

                    if (messsize.length >= 2 && !messsize[0].equals("")
                            && !messsize[1].equals("")) {
                        // Parse first argument
                        int messageNo = -1;

                        try {
                            messageNo = Integer.parseInt(messsize[0]);
                        } catch (Exception e) {
                            // Bad argument
                            messageNo = -1;
                        }

                        // Parse second argument
                        int bodySize = -1;

                        try {
                            bodySize = Integer.parseInt(messsize[1]);
                        } catch (Exception e) {
                            // Bad argument
                            bodySize = -1;
                        }

                        try {
                            return getTopListing(messageNo - 1, bodySize);
                        } catch (JMailMessageNonExistantException e) {
                            return "-ERR TOP message non-existant";
                        }
                    }
                    return "-ERR TOP bad arg";
                }
                // No argument exists
                return "-ERR TOP requires arg";
            }
            return "-ERR AUTH first";
        } // NOOP
        else if (arg1.equals("NOOP")) {
            if (currState == STATE_TRANSACTION) {
                return "+OK NOOP is good";
            }
        } // RSET
        else if (arg1.equals("RSET")) {
            if (currState == STATE_TRANSACTION) {
                try {
                    resetAllMessages();
                    return "+OK Tis Done";
                } catch (JMailMessageNonExistantException e) {
                    return "-ERR Could not";
                }
            }
            return "-ERR AUTH first";
        } // DELE
        else if (arg1.equals("DELE")) {
            if (currState == STATE_TRANSACTION) {
                if (myArgs.length >= 2 && !myArgs[1].equals("")) {
                    // An argument exists
                    int messageNo = -1;

                    try {
                        messageNo = Integer.parseInt(myArgs[1]);
                    } catch (Exception e) {
                        // Bad argument
                        messageNo = -1;
                    }

                    try {
                        if (myUser.getMessageState(messageNo - 1)
                                != JMailPOP3Message.STATE_MARKED) {
                            // Mark specified message
                            myUser.setMessageState(messageNo - 1,
                                    JMailPOP3Message.STATE_MARKED);
                            return "+OK DELE marked message " + messageNo;
                        }
                        return "-ERR DELE already deleted";
                    } catch (JMailMessageNonExistantException e) {
                        return "-ERR DELE bad arg";
                    }
                }
                // No argument exists
                return "-ERR DELE requires arg";
            }
            return "-ERR AUTH first";
        }

        // Command unrecognised
        return "-ERR Command unrecognised";
    }

    /* This method checks whether [thePass] is valid for [myUser] */

    /** This method checks whether the user password is correct
     * @param thePass cleartext password of the user
     * @return true if password is correct<br>
     * false otherwise
     */
    private boolean checkPass(String thePass) {
        if (thePass.equals(myUser.getPass())) {
            return true;
        }
        return false;
    }

    /** When the user sends 'STAT' to the server, the server
     *  returns the total size of all unmarked messages
     * @return string containing the total size of all unmarked messages
     */
    private String getSummaryDropListing() throws JMailMessageNonExistantException {
        String toReturn = null;
        int totalSize = 0;
        int count = 0;

        for (int i = 0; i < myUser.getMessageCount(); i++) {
            int tempState = myUser.getMessageState(i);

            if (tempState == JMailPOP3Message.STATE_NORMAL) {
                int tempSize = myUser.getMessage(i).length();

                totalSize += tempSize;
                count++;
            }
        }

        toReturn = "+OK " + count + " " + totalSize;

        return toReturn;
    }

    /** When the user sends 'LIST' to the server, the server
     *  returns the total size of all unmarked messages along
     *  with the size of each message
     * @return string containing total size of all unmarked messages and size of each message
     */
    private String getDropListing() throws JMailMessageNonExistantException {
        String toReturn = null;
        String middle = "";
        int totalSize = 0;
        int count = 0;
        int messageCount = 0;

        for (int i = 0, max = myUser.getMessageCount(); i < max; i++) {
            if (myUser.getMessageState(i) == JMailPOP3Message.STATE_NORMAL) {
                int tempSize = myUser.getMessage(i).length();

                totalSize += tempSize;

                middle += (count + 1) + " " + tempSize + "\r\n";
                messageCount++;
            }
            count++;
        }

        toReturn = "+OK " + messageCount + " messages (" + totalSize
                + " octets)\r\n";
        toReturn += middle;
        toReturn += ".";

        return toReturn;
    }

    /** When the user sends 'LIST x' to the server, the server
     *  returns the size of message x
     * @return the droplisting of message if exists<br>
     * else error message
     * @param messageNo id of message to get size of
     */
    private String getDropListing(int messageNo) throws JMailMessageNonExistantException {
        int tempState = myUser.getMessageState(messageNo);

        if (tempState == JMailPOP3Message.STATE_NORMAL) {
            int messageSize = myUser.getMessage(messageNo).length();

            return "+OK " + (messageNo + 1) + " " + messageSize;
        } else {
            return "-ERR marked for deletion";
        }
    }

    private String getTopListing(int messageNo, int bodySize) throws JMailMessageNonExistantException {
        if (bodySize >= 0) {
            // Get content of specific message
            String toRet = "+OK Header plus top " + bodySize
                    + " lines of body\r\n";

            String topString = "";

            if (bodySize > 0) {
                LineNumberReader read = new LineNumberReader(
                        new StringReader(myUser.getMessageBody(messageNo)));

                int i = 0;

                try {
                    String temp = null;

                    while (read.ready() && i < bodySize) {
                        temp = read.readLine();
                        if (temp.equals(".")) {
                            break;
                        }
                        topString += temp + "\r\n";
                        i++;
                    }
                } catch (Exception e) {}
            }
            return toRet + myUser.getMessageHeaders(messageNo) + topString + ".";
        }
        return "-ERR TOP Bad Arg";
    }

    /** When the user sends 'RSET' to the server, the server
     *  unmarks all the messages which are to be deleted.
     *  [This method is also called if a connection
     *    is ended prematurely]
     * @return true if all messages were successfully unmarked<br>
     * false otherwise
     */
    public boolean resetAllMessages() throws JMailMessageNonExistantException {
        for (int i = 0; i < myUser.getMessageCount(); i++) {
            if (!myUser.setMessageState(i, JMailPOP3Message.STATE_NORMAL)) {
                return false;
            }
        }
        return true;
    }

    /** When the user sends 'QUIT' to the server, the server
     *  commits all changes made during the connection
     * @return true if successful<br>
     * false if unsuccessful
     */
    private boolean commitChanges() throws JMailMessageNonExistantException {
        myUser.setInPOP3Session(false);

        for (int i = 0; i < myUser.getMessageCount(); i++) {
            int tempState = myUser.getMessageState(i);

            if (tempState == JMailPOP3Message.STATE_MARKED) {
                if (!myUser.deleteMessage(i)) {
                    return false;
                }
            }
        }

        return true;
    }
}


class JMailSMTPConnection {
    public static final int STATE_HELO = 110;
    public static final int STATE_FROM = 120;
    public static final int STATE_TO = 130;
    public static final int STATE_DATA = 140;
    public static final int STATE_DATA2 = 150;
    public static final int STATE_END = 200;

    /** This variable holds the Socket relating to the connection */
    private Socket relatedSocket = null;

    /** This variable holds the current state of the connection */
    private int currState = 0;

    /** This is the value given with the HELO statement.
     * It is used when tranmiting the SMTP message
     */
    private String myHELOName = null;
    private String myClientName = null;

    /** This is the InetAddress relating to the current Connection */
    private InetAddress myClientIP = null;

    /** This is the value given with the MAIL FROM statement.
     * It is used when transmiting the SMTP message
     */
    private JMailEmailCombo mySender = null;

    /** This is a Vector of all the values given with the MAIL FROM statements.
     * It is used when transmiting the SMTP message
     */
    private JMailEmailComboList myRecipents = null;

    /** This is the body of the SMTP message */
    private String myBody = "";

    public JMailSMTPConnection(Socket thatSocket) {
        this.currState = STATE_HELO;
        this.relatedSocket = thatSocket;
        this.myRecipents = new JMailEmailComboList();
    }

    public int getCurrState() {
        return currState;
    }

    public JMailEmailCombo checkEmail(String testEmail) {
        // To pass the email must match /(\w+*@(\w+\.)*\w+/
        String emailRegex = "(\\w+\\.)*\\w+@(\\w+\\.)*\\w+";
        boolean goodEmail = false;
        String[] toReturn = null;

        JMailServer.printDebug("CheckEmail: Input => '" + testEmail + "'");

        if (testEmail.matches("^" + emailRegex + "$")) {
            JMailServer.printDebug("CheckEmail: Matches Simple");
            goodEmail = true;
            toReturn = testEmail.split("@", 2);
        } else if (testEmail.matches("^.*<" + emailRegex + ">$")) {
            JMailServer.printDebug("CheckEmail: Matches Complex");
            goodEmail = true;
            // Get the string between the < and >
            int startPos = testEmail.indexOf('<');
            int endPos = testEmail.indexOf('>', startPos);

            String middleBit = testEmail.substring(startPos + 1, endPos);

            toReturn = middleBit.split("@", 2);
        }

        if (goodEmail) {
            JMailServer.printDebug(
                    "CheckEmail: " + toReturn[0] + "@" + toReturn[1]);
            return new JMailEmailCombo(testEmail, toReturn[0], toReturn[1]);
        }
        return null;
    }

    public String processInput(String theInput) {
        String toReturn = null;
        String arg1 = "";

        if (currState == STATE_DATA) {
            return processData(theInput);
        }

        try {
            if (theInput.toUpperCase().split(" ")[0].equals("QUIT")) {
                currState = STATE_END;
                return "221 Good for you; Smoking is bad";
            }
        } catch (Exception e) {// theInput doesn't contain QUIT
        }

        // Some other state
        if (JMailServer.isOnline) {
            // Server is Online
            return processStates(theInput) + "\r";
        }

        // Server is offline
        return "421 Service Not Available\r";
    }

    /** This method is used when the Connection is in the 'Collect body' state
     * @param dataString string to append to the body
     * or if equals(".") then leave data state
     * @return null
     */
    private String processData(String dataString) {
        if (dataString.equals(".")) {
            // This is the end of data collection
            // Put the message in the queue

            JMailServer.mySMTPQueue.addMessage(myHELOName, myClientName,
                    myClientIP, mySender, myRecipents, myBody);
            int theLength = myBody.length();

            // Reset all the vars
            mySender = null;
            myRecipents = new JMailEmailComboList();
            myBody = "";
            currState = STATE_FROM;

            return "250 " + theLength + " bytes received. Message accepted\r";
        }

        myBody += dataString + "\r\n";
        return null;
    }

    /** This method processes the inputString.
     * Checking whether the protocol is being followed.
     * @param theInput string to parse
     * @return message to return to client
     */
    private String processStates(String theInput) {
        if (theInput == null) {
            // Welcome message
            return "220 " + JMailServer.myHostname + " JMail SMTP Server; "
                    + new Date();
        }

        // Something useful to parse
        String[] myArgs = theInput.split(" ", 2);
        String arg1 = myArgs[0].toUpperCase();

        // HELO
        if (arg1.equals("HELO")) {
            if (myArgs.length >= 2 && !myArgs[1].equals("")) {
                myHELOName = myArgs[1];
                myClientName = relatedSocket.getInetAddress().getHostName();
                myClientIP = relatedSocket.getInetAddress();
                currState = STATE_FROM;
                return "250 " + JMailServer.myHostname + " Hello "
                        + myClientName + " [" + myClientIP.getHostAddress()
                        + "], pleased to meet you. ";
            }

            // Problem with number of args
            return "501 HELO requires domain address";
        } // MAIL
        else if (arg1.equals("MAIL")) {
            if (currState == STATE_FROM) {
                if (myArgs.length >= 2 && !myArgs[1].equals("")) {
                    String[] fromArgs = myArgs[1].split(":", 2);

                    if (fromArgs[0].toUpperCase().equals("FROM")
                            && fromArgs.length == 2 && !fromArgs[1].equals("")) {
                        String tempFrom = fromArgs[1].trim();
                        JMailEmailCombo userDom = checkEmail(tempFrom);

                        // Email address is OK
                        if (userDom != null) {
                            mySender = userDom;
                            currState = STATE_TO;
                            return "250 <" + mySender.parsed()
                                    + ">... Sender ok";
                        }

                        // Email address not correct
                        return "501 Email address invalid";
                    }
                }
                // Some problem with number of args
                return "501 Syntax Error";
            }

            // Incorrect state
            return "503 Bad sequence of commands";
        } // RCPT
        else if (arg1.equals("RCPT")) {
            if (currState == STATE_TO) {
                if (myArgs.length >= 2 && !myArgs[1].equals("")) {
                    String[] toArgs = myArgs[1].split(":", 2);

                    if (toArgs[0].toUpperCase().equals("TO")
                            && toArgs.length == 2 && !toArgs[1].equals("")) {
                        String tempTo = toArgs[1].trim();
                        JMailEmailCombo userDom = checkEmail(tempTo);

                        // Email address is OK
                        if (userDom != null) {
                            // Email is local
                            if (userDom.isDomainServerLocal()) {
                                try {
                                    JMailServer.allUsers.getUser(userDom.user());
                                    myRecipents.add(userDom);
                                    return "250 <" + userDom.parsed()
                                            + ">... Recipent ok (Local)";
                                } catch (JMailUserNonExistantException e) {
                                    // No such local user
                                    return "550 <" + userDom.parsed()
                                            + ">... No such local user";
                                }
                            }
                            // Email is not local
                            // Should be 251 but noone likes that
                            myRecipents.add(userDom);
                            return "250 <" + userDom.parsed()
                                    + ">... Recipent ok; will forward onwards";
                        }

                        // Email address not correct
                        return "501 Email address invalid";
                    }
                }
                // Some problem with number of args
                return "501 Syntax Error";
            }

            // Incorrect state
            return "503 Bad sequence of commands";
        } // DATA
        else if (arg1.equals("DATA")) {
            if (currState == STATE_TO) {
                // Now we ask for data to send
                currState = STATE_DATA;
                return "354 Enter Mail, end with \".\" on a line by itself";
            }

            // Incorrect state
            return "503 Bad sequence of commands";
        } // RSET
        else if (arg1.equals("RSET")) {
            mySender = null;
            myRecipents = new JMailEmailComboList();
            myBody = "";
            currState = STATE_FROM;
            return "250 Reset State";
        } // NOOP
        else if (arg1.equals("NOOP")) {
            return "250 All OK";
        }

        // Command unrecognised
        return "500 Syntax Error; Command unrecognised";
    }
}


class JMailSMTPClientConnection {
    public static final int STATE_HELO = 210;
    public static final int STATE_FROM = 220;
    public static final int STATE_TO = 230;
    public static final int STATE_DATA = 240;
    public static final int STATE_DATA2 = 241;
    public static final int STATE_DATA3 = 242;
    public static final int STATE_QUIT = 250;
    public static final int STATE_END = 3000;

    /** This variable holds the Socket relating to the connection */
    private Socket relatedSocket = null;

    /** This variable holds the current state of the connection */
    private int currState = 0;

    /** sender of the SMTP message */
    private JMailEmailCombo mySender = null;

    /** recipient of the SMTP message */
    private JMailEmailCombo myRecipent = null;

    /** body of the SMTP message */
    private String myBody = null;

    public JMailSMTPClientConnection(Socket thatSocket, JMailEmailCombo sender, JMailEmailCombo recipent, String body) {
        this.currState = STATE_HELO;
        this.relatedSocket = thatSocket;
        this.mySender = sender;
        this.myRecipent = recipent;
        this.myBody = body;
    }

    public int getCurrState() {
        return currState;
    }

    public String processInput(String theInput) {
        String toReturn = null;
        String arg1 = "";

        if (theInput == null) {
            currState = STATE_END;
        } else {
            // Something useful

            String[] myArgs = theInput.split(" ", 2);

            arg1 = myArgs[0];

            // Check for 421 (QUIT)
            if (currState != STATE_DATA && myArgs[0].toUpperCase().equals("421")) {
                currState = STATE_END;
            } // Some other command
            else {
                switch (currState) {
                case STATE_HELO:
                    if (myArgs[0].equals("220")) {
                        // Good response
                        toReturn = "HELO " + JMailServer.myHostname;
                        currState = STATE_FROM;
                    } else {
                        toReturn = "QUIT";
                        currState = STATE_QUIT;
                    }
                    break;

                case STATE_FROM:
                    if (myArgs[0].equals("250")) {
                        // Good response
                        toReturn = "MAIL FROM: " + mySender.unparsed();
                        currState = STATE_TO;
                    } else {
                        toReturn = "QUIT";
                        currState = STATE_QUIT;
                    }
                    break;

                case STATE_TO:
                    if (myArgs[0].equals("250")) {
                        // Good response
                        toReturn = "RCPT TO: " + myRecipent.unparsed();
                        currState = STATE_DATA;
                    } else {
                        toReturn = "QUIT";
                        currState = STATE_QUIT;
                    }
                    break;

                case STATE_DATA:
                    if (myArgs[0].equals("250") || myArgs[0].equals("251")) {
                        // Good response
                        toReturn = "DATA";
                        currState = STATE_DATA2;
                    } else {
                        toReturn = "QUIT";
                        currState = STATE_QUIT;
                    }
                    break;

                case STATE_DATA2:
                    if (myArgs[0].equals("354")) {
                        // Good response
                        toReturn = myBody;
                        currState = STATE_DATA3;
                    }
                    break;

                case STATE_DATA3:
                    if (myArgs[0].equals("250")) {
                        toReturn = "QUIT";
                        currState = STATE_QUIT;
                    }
                    break;

                case STATE_QUIT:
                    if (myArgs[0].equals("221")) {
                        toReturn = "";
                        currState = STATE_END;
                    }
                    break;

                default:
                    toReturn = "QUIT";
                    currState = STATE_QUIT;
                }
            }
        }
        return toReturn + "\r";
    }
}


///////////////////////////////////////////

class JMailUserStore {

    /** Vector of JMailUser objects used for storing messages */
    private Vector<JMailUser> myUsers = null;

    public JMailUserStore() {
        myUsers = new Vector<JMailUser>();
    }

    public Enumeration<JMailUser> getUsers() {
        return myUsers.elements();
    }

    private void getUserExists(String name) throws JMailUserExistsException, JMailUserNonExistantException {
        getUser(name);

        // No exception thrown yet so user exists
        throw new JMailUserExistsException("User Exists");
    }

    public JMailUser getUser(String name) throws JMailUserNonExistantException {
        for (int i = 0; i < myUsers.size(); i++) {
            JMailUser temp = myUsers.get(i);

            if (temp.getName().equals(name)) {
                return temp;
            }
        }
        throw new JMailUserNonExistantException("User Non-Existant");
    }

    public boolean addUserObject(JMailUser object) {
        myUsers.add(object);
        JMailServer.fileio.saveUser(object.getName());
        JMailServer.fileio.saveUserList();
        return true;
    }

    public boolean addUser(String name, String pass) throws JMailUserExistsException {
        String lowername = name.toLowerCase();

        if (!lowername.matches("[a-z]{1,13}")) {
            return false;
        }

        if (!lowername.matches("\\w{1,13}")) {
            return false;
        }

        try {
            getUser(name);
        } catch (JMailUserNonExistantException e) {// OK to add
        }

        JMailUser temp = new JMailUser(lowername, pass);

        addUserObject(temp);
        return true;
    }
}


class JMailUser implements Serializable {
    private static final long serialVersionUID = 1L;

    /** username of the JMailUser */
    private String myName = null;

    /** password of the JMailUser */
    private String myPass = null;

    /** Vector of JMailPOP3Message objects related to the JMailUser */
    private JMailPOP3MailBox myMessages = null;

    /** Whether the user is involved in the POP3 session */
    private transient boolean isInPOP3Session = false;

    public JMailUser(String name, String pass) {
        this.myName = name;
        this.myPass = pass;
        myMessages = new JMailPOP3MailBox();
    }

    public String getName() {
        return myName;
    }

    public String getPass() {
        return myPass;
    }

    public boolean isInPOP3Session() {
        return isInPOP3Session;
    }

    public void setInPOP3Session(boolean value) {
        isInPOP3Session = value;
    }

    public boolean addMessage(String body) {
        boolean good = myMessages.addMessage(body);

        if (good) {
            JMailServer.fileio.saveUser(this.myName);
        }
        return good;
    }

    public int getMessageCount() {
        return myMessages.getMessageCount();
    }

    public int getMessageState(int messageNo) throws JMailMessageNonExistantException {
        return myMessages.getMessageState(messageNo);
    }

    public boolean setMessageState(int messageNo, int newState) throws JMailMessageNonExistantException {
        return myMessages.setMessageState(messageNo, newState);
    }

    public String getMessage(int messageNo) throws JMailMessageNonExistantException {
        return getMessageHeaders(messageNo) + "\n\n" + getMessageBody(messageNo);
    }

    public String getMessageHeaders(int messageNo) throws JMailMessageNonExistantException {
        return myMessages.getMessageHeaders(messageNo);
    }

    public String getMessageBody(int messageNo) throws JMailMessageNonExistantException {
        return myMessages.getMessageBody(messageNo);
    }

    public boolean deleteMessage(int messageNo) throws JMailMessageNonExistantException {
        return myMessages.deleteMessage(messageNo);
    }
}


class JMailEmailComboList implements Serializable {
    public static final long serialVersionUID = 1L;

    private Vector<JMailEmailCombo> emails = new Vector<JMailEmailCombo>();

    public void add(JMailEmailCombo temp) {
        emails.add(temp);
    }

    public Enumeration<JMailEmailCombo> elements() {
        return emails.elements();
    }
}


class JMailEmailCombo implements Serializable {
    public static final long serialVersionUID = 1L;

    private String _unparsed = null;
    private String _user = null;
    private String _domain = null;

    public JMailEmailCombo(String unparsed, String user, String domain) {
        this._unparsed = unparsed;
        this._user = user;
        this._domain = domain;
    }

    public String unparsed() {
        return _unparsed;
    }

    public String parsed() {
        return user() + "@" + domain();
    }

    public String user() {
        return _user;
    }

    public String domain() {
        return _domain;
    }

    public boolean isDomainServerLocal() {
        JMailServer.printDebug("EmailCombo: ServerLocal? '" + domain() + "'");

        if (domain().equals(JMailServer.myHostname)) {
            return true;
        }
        return false;
    }

    /** This method returns true if the hostname matches the JMailServer.NETWORK_LOCAL_REGEX
     * regular expression
     * @return true if hostname matches regular expression<br>
     * false otherwise
     */
    public boolean isDomainNetworkLocal() {
        JMailServer.printDebug("EmailCombo: NetworkLocal? '" + domain() + "'");

        if (domain().matches(JMailServer.NETWORK_LOCAL_REGEX)) {
            return true;
        }
        return false;
    }
}


class JMailPOP3MailBox implements Serializable {
    private static final long serialVersionUID = 1L;

    private Vector<JMailPOP3Message> myMessages = null;

    public JMailPOP3MailBox() {
        myMessages = new Vector<JMailPOP3Message>();
    }

    public Enumeration<JMailPOP3Message> getMessages() {
        return myMessages.elements();
    }

    private boolean getMessageExists(int messageNo) {
        if (messageNo >= 0 && messageNo < getMessageCount()) {
            return true;
        }
        return false;
    }

    private JMailPOP3Message getMessage(int messageNo) throws JMailMessageNonExistantException {
        if (getMessageExists(messageNo)) {
            return myMessages.elementAt(messageNo);
        }
        throw new JMailMessageNonExistantException("Message Non-Existant");
    }

    /** Returns the message state
     * @param messageNo id of the message
     * @return currState of the message<br>
     * <li>STATE_NORMAL: the normal state</li>
     * <li>STATE_MARKED: marked for deletion</li>
     */
    public int getMessageState(int messageNo) throws JMailMessageNonExistantException {
        return getMessage(messageNo).getState();
    }

    public String getMessageHeaders(int messageNo) throws JMailMessageNonExistantException {
        return getMessage(messageNo).getHeaders();
    }

    public String getMessageBody(int messageNo) throws JMailMessageNonExistantException {
        return getMessage(messageNo).getBody();
    }

    public boolean addMessage(String body) {
        myMessages.add(new JMailPOP3Message(body));
        return true;
    }

    public boolean setMessageState(int messageNo, int newState) throws JMailMessageNonExistantException {
        return getMessage(messageNo).setState(newState);
    }

    public boolean deleteMessage(int messageNo) throws JMailMessageNonExistantException {
        return myMessages.remove(getMessage(messageNo));
    }

    public int getMessageCount() {
        return myMessages.size();
    }
}


class JMailMessage implements Serializable {
    public static final long serialVersionUID = 1L;

    protected String myBody = null;

    public String getBody() {
        return myBody;
    }
}


class JMailPOP3Message extends JMailMessage implements Serializable {
    public static final long serialVersionUID = 1L;

    public static final int STATE_NORMAL = 1;
    public static final int STATE_MARKED = 2;
    public static final int STATE_UNKNOWN = 3;

    //////////////////////////

    /** current state of the message<br>
     * <li>STATE_NORMAL: the normal state</li>
     * <li>STATE_MARKED: marked for deletion</li>
     */
    private transient int myState = STATE_NORMAL;

    private String myHeaders = null;

    public JMailPOP3Message(String body) {
        String[] headbody = body.replaceAll("\r\n", "\n").split("\n\n", 2);

        this.myHeaders = headbody[0];
        this.myBody = headbody[1];
    }

    public boolean setState(int newState) {
        myState = newState;
        return true;
    }

    public int getState() {
        return myState;
    }

    public String getHeaders() {
        return myHeaders;
    }

}


class JMailSMTPMessage extends JMailMessage {
    public static final long serialVersionUID = 1L;

    /** date the SMTPMessage was delivered */
    private Date myDate = null;

    private String myHELOName = null;

    /** This is the name of the Client that the SMTPMessage was received from */
    private String myClientName = null;

    /** This is the InetAddress that the SMTPMessage was received from */
    private InetAddress myClientIP = null;

    /** This is the email address of the sender */
    private JMailEmailCombo mySender = null;

    /** This is a Vector of all the recipient email addresses */
    private JMailEmailComboList myRecipents = null;

    public JMailSMTPMessage(String HELOName, String clientName, InetAddress clientIP, JMailEmailCombo sender, JMailEmailComboList recipents, String body) {
        this.myDate = new Date();
        this.myHELOName = HELOName;
        this.myClientName = clientName;
        this.myClientIP = clientIP;
        this.mySender = sender;
        this.myRecipents = recipents;
        this.myBody = body;
    }

    public Date getDate() {
        return myDate;
    }

    public String getHELOName() {
        return myHELOName;
    }

    public String getClientName() {
        return myClientName;
    }

    public InetAddress getClientIP() {
        return myClientIP;
    }

    public JMailEmailCombo getSender() {
        return mySender;
    }

    public JMailEmailComboList getRecipents() {
        return myRecipents;
    }
}
