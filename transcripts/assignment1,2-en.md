# CS6650 Distributed System Assignment

## User

Okay, here\'s our new assignment. Take a look at it first, and we\'ll
discuss it later.

## Claude

I\'ve finished reading it. This assignment transformed the \"fake\"
server in Assignment 1 into a real distributed pipeline. Here\'s a
summary of my understanding, along with a few key points that will be
discussed later.

## Overall Architecture

    Client (A1) of client) 
    │ POST swipe ▲ WebSocket push real leaderboard 
    ▼ │ 
    ALB ──▶ server-v2 × N (ingest + WebSocket endpoint) 
    │ Publish message ▲ Subscribe to leaderboard updates 
    ▼ │
           RabbitMQ (standalone) EC2) |
           skitrak.exchange（topic） │ 
    resort.1 … resort.10 │ 
    │ │ 
    ▼ │
           Consumer (individual) EC2) |
           Aggregation: Vertical 
    for each skier │       Maintenance: Top-N for each resort ─────┘ (backplane) or co-location

## What do the four parts do respectively?

**Part 1: The server becomes a producer.** After receiving a valid swipe
, look up the table to get the lift . Vertically, assembles messages
into a specified format and publishes them. RabbitMQ. The key point
is\... **ChannelPool** : Only one is used across the entire server.
Connection, channel Pre-create them and put them in the pool. Borrow
them each time you publish and return them after use. Never create a new
pool for every publish. The channel also needs to handle reconnection
after a RabbitMQ connection is lost. This is exactly the same idea as
the HTTP connection pool in A1 , except this time you need to implement
the pool yourself.

**Part 2: Consumers, Aggregators, and Real Rankings.** This is the core
of the entire assignment, containing three challenging aspects:

- **Aggregation** : Sum of each skier\'s daily data. Vertically, and
  maintain the top-N rankings for each resort .
- **Connection location issue** : WebSocket The connection is on one
  server-v2 machine , but the new leaderboard is calculated on another
  machine. The consumer cannot directly push content to subscribers. You
  need to\... backplane (broadcast) and Choose one of the options
  between co-location (by resort partition) and explain your reasoning.
- **Delivery Guarantee** : At least one delivery (manual delivery only
  after processing). (ack), deduplication by eventId to achieve
  idempotency, and ensure that messages within the same resort are
  processed in order.

**Part 3: Load Balancing.** Use ALB to distribute traffic to multiple
servers. server-v2, configuration `/health` The health endpoint you
wrote in A1 comes in handy here. It performs a health check, enables a
sticky WebSocket session, and sets the idle timeout to greater than 60
seconds.

**Part 4: Optimization and Testing.** The goal is to keep the queue
depth stable below 1000 and the consumer\... Delay less than 100ms, no
message loss. Testing is required on 1 and 2 machines respectively.
Taiwan, 4 The server will be used for a final stress test with 1 million
records.

## A few points I noticed that require further discussion later.

**1. There is a contradiction between the order requirement and the
number of consumer threads.** The assignment requires testing with 10,
20, 40, and 80 consumer threads, but also stipulates that messages from
the same resort cannot be consumed concurrently by multiple threads to
avoid disrupting the order. However , there are only 10 resort queues .
This means that, under strict order control, a maximum of only 10
threads can actually consume messages; the extra threads must either be
idle or require alternative design considerations. This is a point worth
careful consideration.

Another interesting question: vertical The summation follows the
commutative law; add A first, then add\... B, and adding B first and
then adding A. The result is exactly the same. So the order doesn\'t
actually affect the final total. Then, in what scenarios does it matter?
This kind of question, which requires your own reasoning, is exactly
what you \'re asked to write in the reflection on AI usage.

**2. The frequency of the ranking broadcast.** Assignment requirements:
\"top-N\" \"Push once there\'s a change.\" But under the pressure of
thousands of swipes per second , the top-N\... The frequency of changes
could be so high that it could cause a surge in push volume,
overwhelming both the backplane and WebSocket . Some consolidation might
be necessary, such as limiting pushes to once every few hundred
milliseconds per resort .

**3. Memory allocation for deduplicated sets.** Deduplication by eventId
means remembering the IDs that have already been processed . A stress
test with 1 million records would require 1 million unique identifiers.
For UUIDs, the upper limit of the set and the expiration policy need to
be considered.

**4. The timing of posting a message and returning a 201 error.** It
returns immediately upon sending the message. 201, or wait for RabbitMQ
to confirm receipt (publisher) (Confirms) and then return? The former is
faster, the latter is more reliable, which will directly affect your
throughput and the goal of \"no lost messages\".

**5. Cost.** This time, a maximum of 4 RabbitMQ instances , consumers,
and machines are required . server-v2, plus the machines that might be
running clients, makes a total of six or seven. EC2, plus one ALB. The
free quota is 750 hours per month across all instances , which is easily
exceeded. There may be a separate charge. It\'s best to conduct tests in
one go and stop the instance promptly after completion.

**6. Reflections on the Use of AI (5) (Points must be submitted).** This
time, we are required to write `In the AI_USAGE.md file `, list at least
two suggestions you adopted and two suggestions you rejected or
modified. Also, explain one instance where the AI made a mistake or
where your own reasoning led to the decision. Therefore, during the
discussion, you can consciously record your judgment and decision-making
process. In our last conversation, I corrected myself\... `backoffMs`
Regarding thread safety issues, you also pointed out places where I
didn\'t understand your meaning, and these are all real-world examples
that can be used for writing.

## Suggested order of implementation

1.  Build on EC2 RabbitMQ, create an exchange and 10 queues.
2.  Implement ChannelPool and message publishing in server-v2 , and
    ensure that acknowledgment messages can enter the queue.
3.  Write For consumers, initially only perform aggregation;
    otherwise\... WebSocket, confirm the calculation result is correct.
4.  accomplish Backplane pushes real leaderboards to subscribers.
5.  A1 client once on a single server .
6.  Configuration ALB, testing 2 and 4 servers.
7.  Optimize, organize test results and documentation.

The deadline is November 8th , which is about two and a half weeks from
today. Where do you want to start? If you don\'t have a specific idea, I
suggest starting with step 1 , setting up RabbitMQ , as it\'s the
foundation for all subsequent steps.

## User

Wait a minute, let me explain my understanding. The desired outcome is
that the client remains the same, sending swipes to the ALB server. The
server is distributed, with a load balancer. Is this load balancer built
into ALB, or do I need to design it in the code? After receiving the
swipe, the server hands it over to RabbitMQ and returns a 201 status
code. RabbitMQ then passes it to the consumer, which calculates all
swipes, generates a leaderboard, and sends it back to RabbitMQ. RabbitMQ
then notifies all servers via fanout, and the servers push the
leaderboard to the corresponding resort\'s clients based on the updated
content. Is this correct? Please point out any additions or errors.

## Claude

Your understanding is basically correct. Here are a few details to add,
and two points need correction.

## 1. The load balancer is provided by AWS ; you don\'t need to write any code.

ALB (Application Load Balancer) is a managed service provided by AWS .
You **configure it directly in the** AWS console ; you don\'t need to
write any load balancing code. The configuration requirements include:

- **target group group) :** Add your server-v2 instances.
- **Health check** : Path set `/health `, fill in the interval, timeout
  and other parameters as required by the job.
- **Sticky conversation** : Turning on stickiness.
- **Idle timeout** : Set to greater than 60 seconds.

Your code requires almost no modification, as long as each server-v2 can
handle requests independently and has\... `/health` That\'s fine.

The client side also requires minimal modification; simply change the
server address from the IP address of a specific EC2 instance to\...
**The DNS name for ALB** . POST requests and WebSocket connections both
go through ALB .

## 2. Server-side: Validation → Table lookup → Publish → Return 201

This part is understood correctly. One additional detail: the server
publishes the message to\... Instead of sending the message directly to
a queue, it **is exchanged . Specifically, the message is published
to\...** `skitrak.exchange `, routing key set to `resort.7 `, RabbitMQ
Then route the message to the appropriate location based on the routing
key. `resort.7` This queue.

As for \"returning as soon as it was released\" Whether to use \"201\"
or \"wait for RabbitMQ to confirm receipt before returning\" is a design
decision that needs to be made later.

## 3. Consumers: Calculations are performed incrementally, not on all swipes together.

Here\'s a correction to the wording. After a consumer **subscribes to
the queue, RabbitMQ\...** The message will continue to be pushed to it.
Each time a consumer receives a message\... Swipe performs only one
incremental update:

    Received a message swipe (skier) 123, vertical 200) → 
    cumulative vertical value of skier 123 += 200 
    → check if this skier has entered the range as a result. Has the ranking in 
    the top-N changed, or within the top-N, changed? → If the top-N has changed , → Publish a new ranking list 
    , → Acknowledge this message.

It doesn\'t collect all swipes and then perform unified calculations;
instead, it processes them one by one, keeping the leaderboard
up-to-date. Furthermore, each consumer thread is only responsible for a
few sorted queues, rather than processing all swipes.

## 4. Backplane: The orientation is correct, but there is a crucial detail.

First, let\'s correct a term: backplane The Chinese term is \"**背板**\"
(bèibǎn), not \"广告板\" (guǎobǎn). Originally, it referred to the bus
board inside a computer case that connects various expansion cards; any
signal emitted by one card could be received by all other cards. In this
context, it means \"a broadcast channel shared by all servers.\"
However, you could also interpret it as a \"notification board\": any
notice posted by anyone can be seen by everyone.

You understand the overall process correctly: the consumer publishes the
new ranking list to\... **Fanout exchange** (e.g.)
`leaderboard.fanout `, this is related to `skitrak.exchange` Another one
Each server receives the exchange and then only pushes it to its local
clients that have subscribed to that resort .

**The key detail is that each server must have its own dedicated
queue.**

    Incorrect practice: All servers share a single queue, 
    leaderboard.fanout → [Shared Queue] → server-1 and server-2 take turns retrieving messages.
      Result: Each message is only received by one server, and subscribers connected to the other server do not receive updates. 

    Correct approach: Each server has its own queue: 
    leaderboard.fanout ─ ┬→ [server-1 's queue] → server-1 
    ├→ [server-2 's queue] → server-2 
    └→ [server-3 's queue] → server-3
      Result: Each message is replicated to each queue, and all servers receive it.

This is a basic rule of RabbitMQ : **fanout replicates messages to each
bound queue; however, messages in the same queue will only be delivered
to one consumer** (multiple consumers compete for the same message).
Therefore, the common practice is to declare a temporary queue
(exclusive, auto-delete, with a name automatically generated by the
server) when each server starts and bind it to the fanout exchange .
This queue is automatically deleted when the server shuts down.

Also note that the backplane It\'s just one of two options; the other
is\... Co-location. The assignment requires you to choose one and
explain your reasoning. Based on your description, you prefer\... I also
think the backplane is more suitable for this architecture. We will
discuss the trade-offs between these two options in more detail later.

## 5. The scheduled push notifications in A1 need to be removed.

server-v2 still handles the WebSocket connection, but the timer in A1
that pushes fake data every 500ms is no longer needed. Now, pushes are
triggered by messages received by the backplane : a push is made
whenever an update is received.

## Revised complete process

    Client ──POST──▶ ALB ──▶ A server-v2
                              Verify → Search vertical → Publish to skitrak.exchange(resort.7)→ Return to 201 
    │ 
    ▼
                            RabbitMQ: Routing to 
    resort.7 queue│ 
    ▼
                            Consumer: Accumulate vertical values → Update top-N → If there are any changes, publish to leaderboard.fanout → ack 
    │ 
    ▼
                            RabbitMQ: fanout, replicates to the respective queues on each 
    server-v2 instance . │ 
    ▼
                            Each server-v2: Locate the session on the local machine that has subscribed to resort 7 → Push leaderboard 
    | 
    Client ◀──WebSocket (via ALB)──────┘

## User

What\'s the difference between backplane and co-location? Please share
your insights.

## Claude

Both approaches aim to solve the same problem: **calculating the new
leaderboard. The consumer, and the WebSocket connection held by the
subscriber . Servers are often located on different machines.** Their
approaches are exactly opposite:

- **Backplane** : Regardless of which server the subscriber is connected
  to, **the update is broadcast to all servers** , and each server
  decides for itself who to push it to.
- **Co-location** : **By agreeing in advance that** all computations and
  subscribers within the same resort are concentrated on the same
  machine, there is no need to transfer updates across machines.

## Backplane: Let the message find the connection.

                        Consumer (a separate machine that handles all) resort) 
    │ Released new leaderboard for 
    resort 7 ▼ 
    leaderboard.fanout 
    ┌────────┼────────┐ 
    ▼ ▼ ▼ 
    server-1 server-2 server-3 ← Received on each
              (No subscribers 7) (2 subscribers) (1 subscriber)
                throw away      Push to 2  Push to 1

Subscribers can connect to any server, ALB You can allocate it however
you like.

## Co-location: Allows the connection to find data.

         node A (responsible for resorts 1-5)            node B (responsible for resorts 6-10) 
    ├─Consumes resources from resort.1 … resort.5 ├─Consumes resources from resort.6 … resort.10 
    ├─Calculates the leaderboard in memory ├─Calculates the leaderboard in memory 
    └─Holds all subscribers to resorts 1-5 └─Holds all subscribers 
    to resorts 6-10 ↑ ↑ 
    ALB must route /leaderboard/1~5 here ALB must route /leaderboard/6~10 here

Once the calculation is complete, the subscriber push can be found
directly in the local memory without going through any network relay.

## Comparison of the two

  --------------------------------------------------------------
                       Backplane            Co-location
  -------------------- -------------------- --------------------
  Which server can     any machine          the machine
  subscribers connect                       responsible for that
  to?                                       resort .

  Push delay           One more hop:        At the very least,
                       consumer → RabbitMQ  push directly to the
                       → server             local memory.

  message volume       Zoom in: Each update No magnification
                       × Number of servers  

  Add server           It\'s simple, just   It needs to be
                       add it to ALB and it re-divided resort,
                       will work.           migration status

  The upper limit of   There is no limit to A maximum of 10
  expansion            the number of        nodes (because there
                       servers.             are only 10 )
                                            resort)

  Hot issues           Does not exist       a particular resort
                                            becomes very
                                            popular, the nodes
                                            responsible for it
                                            will become
                                            overloaded.

  Node crash           This only affects    these resorts will
                       subscribers already  be interrupted until
                       connected to the     another node takes
                       server; they can     over.
                       simply reconnect to  
                       another server.      

  Implementation       Medium: Each server  It looks simple, but
  complexity           needs to subscribe   failover and
                       to backplane         repartitioning are
                                            complex.
  --------------------------------------------------------------

## My opinion: Backplane would have been a more suitable choice for this assignment.

**Reason 1: The deployment requirements of the operation are more
suitable for the backplane.** The assignment explicitly requires the
consumer to run **on a separate EC2 instance** . However, the essence of
co-location is to have computation and connectivity reside on the same
node, which conflicts with having a separate consumer layer. If we want
to\... Co-location requires merging the consumer into\... server-v2
deviates from the deployment architecture given in the assignment.

**Reason 2: Testing requires continuously adding servers.** You need to
use 1 unit and 2 units respectively. Taiwan, 4 Test on a server. For
backplane, adding a server simply requires registering it with ALB ;
Co-location requires reassigning resort ownership and modifying ALB
routing rules every time the number of servers changes.

**Reason 3: At this scale, backplane Its drawbacks are almost
negligible.** Its main cost is the amplification of information, but
let\'s do the math: 10 indivual Assuming each resort pushes a maximum of
5 leaderboards per second, and there are 4 servers , then the message
volume per second on the backplane is:

    10 × 5 × 4 = 200 messages/second

This is negligible for RabbitMQ . The real pressure is on Ingest
(thousands of messages per second). (This refers to the process of
sending a swipe, not the leaderboard.) The prerequisite is that you
**limit the frequency of leaderboard pushes** , which is the merged push
mentioned earlier. If a push is sent every time a swipe is processed ,
this number will increase several hundred times.

**Reason 4: co-location The advantages of co-location are not apparent
here. The truly valuable scenarios for** co-location are systems with
extremely high update volumes or extremely stringent latency
requirements, such as large chat systems segmented by room. For a
leaderboard, the few milliseconds of latency caused by an extra hop are
completely imperceptible to the user.

## Two areas for further improvement

**1. Use topic exchange instead Fanout reduces useless messages.** A
simple fanout would allow each server to receive updates from all
resorts , even if no one on that local machine is subscribed to a
particular resort. The improved approach is to use topic exchanges,
setting the routing key to\... `leaderboard.7 `; Each server only binds
to **Resort 7 if someone on that local machine has subscribed to it .**
`leaderboard.7 `. This way, each server will only receive the updates it
needs. The cost is that subscribers need to be dynamically bound and
unbound when they first connect and when they last disconnect. For this
assignment, a pure fanout would be perfectly sufficient; this can be
discussed further in the design document.

**2. When a new subscriber connects, immediately send them an updated
leaderboard.** In the backplane scheme, after a new subscriber connects,
they have to wait until the next leaderboard change to receive their
first message. If each server **caches the latest leaderboard** received
from **each resort by** the backplane , in\... `onOpen` Sending it
directly to subscribers of a new connection will provide a much better
experience.

## When written into the design document

The assignment requires you to explain the reasons for your choice and
the trade-offs. You can organize your answer as follows: Explain why you
chose the backplane (it aligns with the requirements of an independent
consumer layer and the need for elastic server expansion), frankly
acknowledge its costs (the latency of an extra hop and message
amplification), then explain how you controlled these costs (limiting
push frequency and optional subscription by resort ), and finally
explain under what circumstances co-location would be a better choice.

This passage can also serve as material for reflection on the use of AI
, but the premise is that you **have actually done your own thinking** ,
such as which reasons you agree with and which reasons you have
different opinions on.

## User

How do I integrate RabbitMQ into my code? Do I need to add a dependency?

## Claude

Yes, RabbitMQ requires a dependency. The official Java client library is
called `amqp-client `is responsible for establishing connections and
creating\... Channels, publishing and consuming messages.

## 1. Add dependencies

exist **server-v2** and **consumer** Two projects `pom.xml` Add the
following to each section:

    < dependency > 
    < groupId >com.rabbitmq</ groupId > 
    < artifactId >amqp-client</ artifactId > 
    < version >5.21.0</ version > 
    </ dependency >

The version number is just an example; you can check the latest stable
version of 5.x on Maven Central .

Both projects need it because:

- **server-v2** : This requires **deploying** swipe to\...
  `skitrak.exchange will `**receive** leaderboard updates from backplane
  again.
- **consumer** : To **consume from** the resort queue Swipe, then
  **publish the leaderboard** to backplane.

**Note: Do not write here.** `<scope>provided `**\</scope\>** This is
the exact opposite of Servlet-API and WebSocket-API . Those two
libraries are provided by Tomcat at runtime , so they don\'t need to be
included. WAR; Tomcat does not come with a RabbitMQ client, so the
default compile scope must be used. `mvn package` At that time, it will
be packaged into WAR . `WEB-INF/lib` Table of Contents. Consumer It is a
standalone program, and like the A1 client, it needs to be packaged into
a fat jar.

## 2. Handle a log warning as well.

`amqp-client` It depends on the SLF4J logging interface. If the project
does not have a specific logging implementation, it will print a log
message at startup. `SLF4J: No SLF4J providers were found` The warning
doesn\'t affect functionality, but it looks a bit odd. A simpler
implementation can be added:

    < dependency > 
    < groupId >org.slf4j</ groupId > 
    < artifactId >slf4j-simple</ artifactId > 
    < version >2.0.13</ version > 
    </ dependency >

## 3. Most basic usage

Let\'s look at a very simple example to understand the whole process.
The ChannelPool function that follows builds upon this by pooling
channels :

    ConnectionFactory factory = new ConnectionFactory (); 
    factory . setHost ( "<RabbitMQ's private IP>" ); 
    factory . setPort ( 5672 ); 
    factory . setUsername ( "skitrak" ); 
    factory . setPassword ( "<password>" ); 

    Connection connection = factory . newConnection ();   // A single TCP connection, shared by the entire service. 
    Channel channel = connection.createChannel ( );      // Create a channel on the connection 

    channel.exchangeDeclare ( "skitrak.exchange" ) "topic " true );   // Declare exchange, durable 
    channel . basicPublish ( "skitrak.exchange" , "resort.7 " null , json . getBytes ( StandardCharsets . UTF_8 ));

The hierarchy here is as follows: **one Connection corresponds to one
TCP connection, and multiple Channels can be opened on top of it.** Both
publishing and consuming messages are done through channels . It is
heavyweight, thread-safe, and shared by the entire application; Channel
It\'s lightweight, but **it can\'t be shared across multiple threads** .
That\'s why the assignment requires you to implement a ChannelPool .

## 4. Where should the connection be created in Tomcat ?

Server-v2 runs in Tomcat and doesn\'t have a main method. So when should
the Connection and ChannelPool be created? The answer is: **they should
be created once when the application starts and closed when the
application shuts down** , not multiple times. `doPost` It is created
for each request.

The Servlet specification provides a dedicated interface for listening
to application startup and shutdown, called\...
`ServletContextListener `:

    @WebListener 
    public class RabbitMQInitializer implements ServletContextListener {
        @Override
        public void contextInitialized ( ServletContextEvent e ) {
            // When the WAR deployment is complete, Tomcat calls this: to create a Connection and a ChannelPool
            // Save to e.getServletContext().setAttribute("channelPool", pool)
        }

        @Override
        public void contextDestroyed ( ServletContextEvent e ) {
            // Called when WAR is uninstalled or Tomcat is shut down: Closes all channels and connections.
        } 
    }

After that, SkierServlet can\... `init()` Li through
`getServletContext().getAttribute("channelPool")` Get this pool. This
relates to what was discussed in A1 regarding Tomcat. \"Responsible for
calling your code\" is the same idea, except this time the
application\'s start and stop events are being called.

## 5. Two points to note regarding the RabbitMQ server.

**1. Default** `guest` **Users are only allowed to log in from localhost
.** Your server-v2 and consumer are both on other machines, so you
can\'t connect using the guest account . The error message is
usually\... `ACCESS_REFUSED `. A new user needs to be created on
RabbitMQ and permissions granted.

    sudo rabbitmqctl add_user 
    skitrak <password> sudo rabbitmqctl set_user_tags skitrak administrator 
    sudo rabbitmqctl set_permissions -p / skitrak ".*" ".*" ".*"

**2. The security group needs to open two ports.**

- **5672** : AMQP Protocol port, server-v2 Used when connecting to
  RabbitMQ with a consumer . Only make it accessible to your own EC2
  servers .
- **15672** : Management interface port. After enabling the management
  plugin, you can view information such as queue depth and message rate
  in your browser. The queue screenshot required for the job comes from
  here; you only need to make this port accessible to your own IP
  address.

your RabbitMQ been installed on EC2 ? If not, we can start with
installation and creation. Exchange and queue start.

## User

After establishing the connection, what I need to do during
initialization is to create a queue corresponding to the resort, right?

## Claude

Yes, but one concept needs clarification: queues are not \"open,\" but
rather \" **declare** .\"

## The queue exists on the RabbitMQ server.

Connection and Channel are **the connections** between your application
and RabbitMQ ; they disappear when the application is closed. Exchanges,
queues, and bindings are **objects created on the RabbitMQ server** ,
much like tables in a database. They only need to be created once and
will persist thereafter (when declared as durable , RabbitMQ\...). (It
still exists after restarting), and it has nothing to do with whether
your program is running or not.

10 queues during initialization \" is **to ensure that these objects
exist on RabbitMQ** . If they already exist, the declaration operation
does nothing; if they don\'t exist, they are created. This operation is
**idempotent** and can be safely called repeatedly.

## The complete structure that needs to be declared

Not just 10 queues, but also including Exchanges, and the binding
relationships between them:

    skitrak.exchange(topic) Type) 
    ├── routing key = resort.1 ── binding ── ▶ Queue resort.1 
    ├── routing key = resort.2 ── binding ── ▶ Queue resort.2 
    │ …… 
    └── routing key = resort.10 ── binding ── ▶ queue resort.10

If the binding is missed, the message will be published to the exchange
and, if no matching queue can be found, it will be **directly discarded
by** RabbitMQ without any error message. This is a very common and
silent reason for message loss.

## code

    channel . exchangeDeclare ( "skitrak.exchange" , BuiltinExchangeType . TOPIC , true );   // durable 

    Map < String , Object > qArgs = new HashMap <>(); 
    qArgs . put ( "x-message-ttl" , 3_600_000 );          // Messages are retained for a maximum of 1 hour 
    . qArgs.put ( " x-max-length" , 1_000_000 );           // The queue can hold a maximum of 1 million messages 
    . qArgs.put ( "x-overflow" ) "reject-publish" );      // When the queue is full, reject new messages instead of discarding old messages 

    . ( int i = 1 ; i <= 10 ; i ++) {
        String name = "resort." + i ; 
    channel . queueDeclare ( name , true , false , false , qArgs );   // durable, non-exclusive, does not automatically delete 
    the channel . queueBind ( name , "skitrak.exchange" , name );       // The routing key and queue name are the same 
    }

`queueDeclare` The parameters are, in order: queue name, whether\...
durable (RabbitMQ) (Whether to retain after restart) exclusive (whether
it belongs only to the current connection), whether The \`auto-delete\`
option (whether to automatically delete when there are no consumers) and
additional parameters.

## the TTL and queue limit requirements of the assignment .

**1. Setting the TTL too short will cause message loss.** Messages are
automatically deleted from the queue if they remain in the queue beyond
their time-to - live (TTL) . If the consumer cannot process the messages
quickly enough, the queue will accumulate, and the messages will quietly
expire, directly violating the \"no message loss\" principle. The goal
of \"swipe\" is to prevent messages from piling up indefinitely in case
of exceptions. Therefore, the TTL should be set long enough (e.g., 1
hour).

**2. The default behavior when the queue is full is to discard the
oldest message.** `x-max-length` The default overflow policy is
`Drop-head `: When the queue is full, new messages continue to arrive as
usual, while the oldest messages are **quietly discarded** without the
producer\'s knowledge. (Change to\...) `reject-publish` Then, when the
queue is full, new messages will be rejected. If combined with publisher
confirms, the producer will know that the message was not successfully
published, and can then retry or return an error.

It\'s also important to distinguish between two numbers: the job\'s
objective of \"keeping the queue depth consistently below 1000 \" is **a
performance metric** , indicating that the consumer is keeping up with
the production speed; while\... `x-max-length` This is **a safety
limit** to prevent memory from running out. If you set it to\... 1000,
once there is a brief surge in traffic, it will immediately start
rejecting messages.

## Who should make the declaration?

There are two common approaches:

- server-v2 **in the code .** Both the declaration and the consumer\'s
  declaration are executed once upon startup. Because the operation is
  idempotent, 4 There will be no problem running both server-v2
  instances , and the startup order doesn\'t matter; whichever starts
  first is responsible for creating the instance.
- **Create in advance using a script** : `rabbitmqadmin` Write an
  initialization script using the command-line tool and place it in the
  assignment requirements. `/deployment` In the directory.

I suggest **combining the two approaches** : write the declaration code
as a public method, server-v2. Shared with consumer ; and at the same
time `/deployment` A script is also provided.

There is an important reason for using public methods: **if two
declarations of the same queue have inconsistent parameters** (for
example, the TTL is set to 1 hour for server-v2 and 2 hours for consumer
), RabbitMQ will encounter issues. Report `PRECONDITION_FAILED` Error
(error code) 406), and directly close this Channels. Declaring them
using the same method ensures consistent parameters. If you modify the
queue parameters during debugging, you need to delete the old queue in
the management interface and then redeclare it.

## User

Do I need to set up exchange and queue separately for deadletter?

## Claude

Yes. The dead letter mechanism works like this: **you create a regular
exchange and queue, and then tell the business queue, \"When messages
become dead letters, send them to this exchange** . \" RabbitMQ doesn\'t
automatically create these objects for you.

## structure

    skitrak.exchange ──▶ resort.1 … resort.10 (business queue) 
    │When a message becomes a dead letter 
    ▼
                         skitrak.dlx (Dead Letter) An exchange is just a regular one. exchange) 
    │ 
    ▼
                         skitrak.dlq (dead letter queue, which is just a regular queue)

The so-called \"dead letter\" An \"exchange\" is no different from a
regular exchange ; it\'s simply **designated by a queue** as the place
to receive dead letters. This is achieved through a parameter of the
business queue. `x-dead-letter-exchange` This is achieved through \[the
means\].

## code

    // 1. First declare the dead-letter exchange and the dead-letter queue, and bind them 
    to the channel.exchangeDeclare ( " skitrak.dlx " , BuiltinExchangeType.TOPIC , true ); 
    channel . queueDeclare ( "skitrak.dlq" , true , false , false , null ); 
    channel . queueBind ( "skitrak.dlq" , "skitrak.dlx" , "#" );   // "# " matches all routing keys // 2. 

    Specify the destination of dead letters in the parameters of the business queue 
    qArgs.put ( "x-dead-letter-exchange" , "skitrak.dlx" ); 
    // Then, as usual, queueDeclare("resort.N", ..., qArgs)

When a message becomes a dead letter, it will **retain the original
routing key by default** , for example\... `resort.7 `. Therefore,
dead-letter exchanges use topic types and are bound. `# `A single
dead-letter queue can receive dead letters from all resorts . After
retrieving a message from the dead-letter queue, its routing key can
still be used to determine which resort it originally belonged to.

## Under what circumstances will a message become a dead letter?

  ---------------------------------------------------------------
  reason                         illustrate
  ------------------------------ --------------------------------
  The consumer rejects the       `basicNack(tag, false, false)`
  message and does not           or `basicReject(tag, false)`
  re-enqueue.                    

  The message exceeded TTL       Expired messages are not deleted
                                 directly, but are instead placed
                                 in the dead letter queue.

  The queue has exceeded its     **Only in** `drop-head` **Under
  length limit.                  this strategy** , old messages
                                 that are pushed out become dead
                                 letters.
  ---------------------------------------------------------------

The third point requires special attention. In the previous round, we
suggested using\... `reject-publish` Under this strategy, rejected
messages are **returned to the producer** and do not enter the
dead-letter queue. If you want rejected messages to also enter the
dead-letter queue, you can use\... `reject-publish-dlx` Strategy.

## Its role in the task: handling \"toxic information\".

The assignment requires implementing \"Failed-delivery retry logic\".
Suppose the consumer consistently fails to process a certain message
(e.g., due to a problem with the message format, resulting in an
exception during parsing). If a retry logic is used every time it
fails\... `basicNack(tag, false, true)` If you try **to re-enqueue** the
message, it will be repeatedly delivered and fail, creating an infinite
loop and blocking all subsequent messages in the queue. This type of
message is called a \"poison message.\" message).

With a dead-letter queue, this can be handled as follows:

    Processing failed → Retry several times locally (e.g., 3 times) 
    → Still failed → BasicNack(tag, false, false), do not re-enqueue 
    → Message enters skitrak.dlq, the business queue continues to process subsequent messages.

0 messages in the dead letter queue \" proves that \"no messages were
lost.\" Strong evidence of "swipe".

Regarding how to count retries: For a regular classic queue,
RabbitMQ\... It will only tell you one message: \"Has it been
redelivered? `"` The flag (of the delivery method) won\'t tell you
exactly how many times the delivery was made. Therefore, the simplest
approach is to retry locally on the consumer \'s **end** , rather than
relying on re-enqueuing. If using a quorum queue, this can be achieved
through\... `x-delivery-limit` The parameter allows RabbitMQ to
automatically count the number of occurrences, and automatically
converts the letter to dead letter once the limit is exceeded.

## A practical reminder: Parameters of an existing queue cannot be directly modified.

If you have already declared `resort.1` arrive `resort.10 `, now added
`x-dead-letter-exchange` When parameters are redeclared, the issue
mentioned in the previous round will arise. `PRECONDITION_FAILED` Error
because the parameters are inconsistent with the existing queue. There
are two solutions:

- **Deleting the old queue and then redeclaring it** is the simplest
  method during development. You can delete it in the management
  interface or execute the command.
  `rabbitmqctl delete_queue resort.1 `.
- Instead of writing them in the queue declaration parameters, **use
  policies to set these parameters** : TTL, length limit, and dead
  letter exchange can all be configured through policies . The advantage
  is that **it can be modified at any time without recreating the
  queue** , which is also the method officially recommended by RabbitMQ
  :

<!-- -->

    sudo rabbitmqctl set_policy skitrak-resorts "^resort\." \
      '{"message-ttl":3600000,"max-length":1000000,"overflow":"reject-publish","dead-letter-exchange":"skitrak.dlx"}' \
      --apply-to queues

This command means: apply the following to all names. `resort.` The
initial queue applies these settings. If using a policy , the code\...
`queueDeclare` This eliminates the need for additional parameters and
avoids inconsistencies in parameter declarations across multiple
programs. This command is also well-suited for inclusion in\...
`/deployment` In the directory\'s initialization script.

## User

If I want to configure these settings for my broker, how exactly do I do
it? I\'ve already established a connection; do I use this connection to
communicate directly?

## Claude

It doesn\'t use Connection directly . **All operations must be performed
through Channel** : Declaration Exchange, queue declaration, binding,
message publishing, and message consumption are all methods on Channel .
It is only responsible for maintaining that TCP connection and does not
provide these operations itself.

Therefore, the process is: **open a new Connection from the existing
one. Use the Channel to complete all settings, then close the Channel.**

## Complete setup code

    public static void declareTopology ( Connection connection ) throws IOException​ TimeoutException {
        try ( Channel ch = connection . createChannel ()) {          // Temporarily open a channel, and automatically close it when finished.

            // 1. Dead letter exchange and dead letter 
    queue ch.exchangeDeclare ( " skitrak.dlx " , BuiltinExchangeType.TOPIC , true ); 
    ch . queueDeclare ( "skitrak.dlq" , true , false , false , null ); 
    ch . queueBind ( "skitrak.dlq" , "skitrak.dlx" , "#" );

            // 2. Business exchange 
    ch . exchangeDeclare ( "skitrak.exchange" , BuiltinExchangeType . TOPIC , true );

            // 3. Parameters of the business queue
            Map < String , Object > args = new HashMap <>(); 
    args . put ( "x-message-ttl" , 3_600_000 ); 
    args . put ( "x-max-length" , 1_000_000 ); 
    args . put ( "x-overflow" , "reject-publish" ); 
    args . put ( "x-dead-letter-exchange" , "skitrak.dlx" );

            // 4. Ten business queues and their bindings to the exchange.
            for ( int i = 1 ; i <= 10 ; i ++) {
                String q = "resort." + i ; 
    ch.queueDeclare ( q ,​​ true , false , false , args ); 
    ch . queueBind ( q , "skitrak.exchange" , q );
            }
        } 
    }

`Channel` Achieved `AutoCloseable` Since it\'s an interface, it can be
placed in a try-with-resources block, and the block will automatically
close when it ends.

## Why use a separate temporary channel?

You can also borrow a channel from the ChannelPool to do these settings,
but it\'s better to create a separate one for two reasons:

1.  **Clear responsibilities : The** channel is configured to execute
    only once at startup, and is no longer needed afterward . Channels
    in the pool are specifically used for publishing messages.
2.  **Errors will not pollute the pool** : If inconsistent parameters
    are declared, triggering\... `PRECONDITION_FAILED` Error, RabbitMQ
    It will **close this channel** , but it will not shut down. If a
    temporary channel is used , only that temporary channel will be
    damaged; if a pooled channel is used\... If you use a channel, you
    will return a closed channel to the pool, and any subsequent thread
    that borrows it will encounter an error when publishing a message.

## Where to call

- **server-v2** : In `ServletContextListener` of `contextInitialized` It
  is called after creating the Connection and before initializing the
  ChannelPool .
- **consumer** : Called in the main method after the connection is
  established but before consumption begins.

Since both sides are calling the same method, the parameters will
naturally be identical. You can put it in a common class, with each
project making a copy, or extract it as a shared module.

## Another way: policy

The policy method mentioned in the previous round **cannot be set
through the Channel** . The AMQP protocol itself can only declare\...
exchange, queue, and binding; policy RabbitMQ \'s management functions
can only be configured through the following three methods:

- **On the machine where RabbitMQ is located** , execute
  `rabbitmqctl set_policy ...` Order.
- **Management interface** : Open in browser
  `http://<RabbitMQ IP>:15672 `, go to Admin → Policies to add it.
- **Managing the HTTP API** : Sending HTTP requests via port 15672 ,
  suitable for writing scripts.

If you decide to use policy, in the code `queueDeclare` There\'s no need
to pass the args parameter (passing\...) `null` (That\'s fine). TTL,
length limit, and dead letter settings can all be handled by the policy
. Choose only one of these two methods; **don\'t use both
simultaneously** , otherwise it will be difficult to determine which
setting is in effect when troubleshooting. For this assignment, passing
parameters directly in the code is simpler and more intuitive; the
policy\... The advantage is that it does not require deleting and
rebuilding the queue when making modifications, making it more suitable
for repeatedly adjusting parameters during the optimization phase.

## How do I confirm after setting it up?

Open the management interface:

- **Exchanges** The page should be visible. `skitrak.exchange` and
  `skitrak.dlx `.
- **Queues** The page should be visible. `resort.1` arrive `resort.10 `,
  and `skitrak.dlq `. Click on any one of them. `resort.N `, you can see
  it in the Features section. TTL, Lim (maximum length), DLX The
  \`bindings\` tag can be seen in the Bindings section.
  `skitrak.exchange` The binding relationship.

Once everything is confirmed to be correct, you can start writing the
ChannelPool .

## User

The key in this rule is rabbitMQ. The rule requires it to be written
this way, right?

## Claude

Yes, args These keys are all names defined by RabbitMQ and must be
written exactly the same, including\... `x-` Prefix and hyphen in the
middle.

## Commonly used queue parameters

  -------------------------------------------------------------------------
  key                           Value type             meaning
  ----------------------------- ---------------------- --------------------
  `x-message-ttl`               Integer, unit:         How long can a
                                milliseconds           message be kept in a
                                                       queue?

  `x-max-length`                Integer                What is the maximum
                                                       number of messages a
                                                       queue can hold?

  `x-max-length-bytes`          Integer                What is the maximum
                                                       number of bytes a
                                                       queue can hold?

  `x-overflow`                  Strings: `drop-head `, What to do when the
                                `reject-publish `,     queue is full?
                                `reject-publish-dlx`   

  `x-dead-letter-exchange`      string, exchange Name  exchange should the
                                                       dead letter be sent
                                                       to?

  `x-dead-letter-routing-key`   String                 The routing key used
                                                       by dead letters (if
                                                       not set, the
                                                       original key will be
                                                       retained).

  `x-expires`                   Integer, unit:         How long after which
                                milliseconds           a queue is not used
                                                       will it be
                                                       automatically
                                                       deleted?

  `x-queue-type`                Strings: `classic `,   Queue types
                                `quorum `, `stream`    
  -------------------------------------------------------------------------

**The format of the value is also predefined.** for example `x-overflow`
The value can only be one of the strings listed in the table above; TTL
It must be an integer; if written as a string\... `"3600000" `, RabbitMQ
It will report an error due to incorrect type. `PRECONDITION_FAILED`
mistake.

## The most common pitfall: key It won\'t report an error if I made a mistake.

If you misspell the key , for example, write it as `x-message-tll` or
`message-ttl `(missed) `x- `), RabbitMQ **It won\'t report any errors**
; it will simply save the parameter as additional information it
doesn\'t recognize and then ignore it. The queue can be created
normally, but the TTL you thought you set actually hasn\'t taken effect
at all.

Therefore, after making the declaration, be sure to confirm it on the
management interface: Open the Queues page and check the Features
section for each queue . TTL, Lim, DLX If these markers are not present,
it means that the corresponding key is written incorrectly.

## Which names did you choose yourself?

It\'s important to distinguish these names: the following are names
**you chose yourself** , such as RabbitMQ. I don\'t care what it\'s
called:

- Exchange names: `skitrak.exchange `, `skitrak.dlx`
- Queue names: `resort.1 `, `skitrak.dlq`
- Routing key: `resort.7`

In this assignment, `skitrak.exchange` and `resort.{resortID}` The
naming format is specified in the assignment, so it must be written
according to the assignment requirements. Wildcards in topic
bindings\... `# `(matches zero or more words) and `* `(matching exactly
one word) is the syntax specified by RabbitMQ .

## If changed to policy, key Different names

The key used in the policy **is missing.** `x-` **Prefix** :

  -------------------------------------------------------------
  Queue parameters (written in   The syntax in policy
  the code)                      
  ------------------------------ ------------------------------
  `x-message-ttl`                `message-ttl`

  `x-max-length`                 `max-length`

  `x-overflow`                   `overflow`

  `x-dead-letter-exchange`       `dead-letter-exchange`
  -------------------------------------------------------------

This is also a point of confusion: if it is written in the policy\...
`x-message-ttl `, or written in the code `Message-ttl `will be silently
ignored because the sender doesn\'t recognize it.

## User

How can I verify that this setup is successful? Is it so that I can see
the specific changes on localhost after running Tomcat?

## Claude

Not in Tomcat `localhost:8080` Look up. Tomcat That interface can only
show whether your servlet is running normally. **Changes occurring on
RabbitMQ need to be viewed through the RabbitMQ management interface** .
Verification can be divided into four steps, confirming each step
sequentially.

## Step 1: Open the RabbitMQ management interface

the EC2 instance where RabbitMQ is located (this only needs to be done
once):

    sudo rabbitmq-plugins enable rabbitmq_management

Then open it in your browser
`http://<RabbitMQ ``'s public ``IP>:15672 `, log in with the user you
created (you cannot use\...) If you can\'t open it, check if port 15672
is open for your IP in your security group .

running Docker on your own computer RabbitMQ, the address is
`http://localhost: `15672

## Step 2: Confirm that server-v2 has successfully connected and completed the declaration.

starting Tomcat , first check the Tomcat logs ( `logs/catalina.out `) to
confirm there are no errors. It is recommended to\...
`contextInitialized` After the declaration is completed, print a log
line, for example\... `The RabbitMQ topology is declared `, making it
easy to confirm whether it has executed successfully.

Special attention should be paid to: **if** `contextInitialized` **If an
exception is thrown, the entire WAR deployment will fail** , and all
endpoints will return an error. 404, continuous `/health` And so if you
find `/health` If you suddenly can\'t access it, first check the logs
here.

Then check each page in the management interface:

  -------------------------------------------------------------
  page                           Content that should be seen
  ------------------------------ ------------------------------
  **Connections**                A connection from the IP
                                 address of server-v2 indicates
                                 that the connection has been
                                 successfully established.

  **Exchanges**                  `skitrak.exchange` and
                                 `skitrak.dlx `, all are of
                                 type topic, Features One
                                 column has `D `(represents)
                                 durable)

  **Queues**                     `resort.1` arrive
                                 `resort.10 `, and
                                 `skitrak.dlq `. The Features
                                 section of the business queue
                                 should have this information.
                                 `D `, `TTL `, `Lim `, `DLX`
                                 These marks

  Click `resort.7` Check         A from `skitrak.exchange `,
  **Bindings**                   routing key is `resort.7`
                                 binding
  -------------------------------------------------------------

If a certain tag is missing in the Features column, it means that the
corresponding parameter key is written incorrectly, which is the
situation we discussed in the previous round where \"writing it
incorrectly will not cause an error\".

## Step 3: Manually send a message to test if the routing is correct.

Before writing the deployment code, you can manually test the routes
using the management interface:

1.  Open **Exchanges** → Click `skitrak.exchange `→ Expand **Publish
    message** .
2.  Enter the routing key. `resort.7 `, Payload Write down any
    paragraph, for example `Test `, then click Publish.
3.  Back **Queues** Page, `resort.7` of **Ready** One column should
    become 1, other queues remain at 0.

If you click Publish , the interface will display a prompt. **The
message was published, but not routed** , indicating that the binding
relationship was not established and the message was discarded.

## Step 4: Test the dead letter queue

Continue from the previous step:

1.  Open `resort.7` Queue details page → Expand **Get messages** .
2.  Ack Mode Selection **Reject requeue false** , then click Get
    Message(s).
3.  This message will be rejected, and you will not be rejoined. Return
    to the Queues page, `resort.7` Return 0, and `skitrak.dlq` It
    becomes 1.

This proves that the dead-letter mechanism is working properly.
Incidentally, if Ack Mode is selected\... **The Nack message requeue
option (true** ) allows you to view the message content without removing
the message from the queue.

## Next: Test with real requests.

After writing the deployment code, use curl to send a message to
server-v2. swipe:

    curl -i -X POST -H "Content-Type: application/json" \
      -d '{"time":217,"liftID":21}' \
      http://<server-v2>:8080/<app>/skiers/7/seasons/2026/days/1/skiers/123

You should be able to see the return. 201, and in the management
interface `resort.7` message count 1. Use Get messages to view the
content of this message and confirm that its format is completely
consistent with the job requirements, including fields such as eventId,
vertical, and serverId .

## If you don\'t want to use a browser, you can also use the command line.

on the machine where RabbitMQ is located:

    sudo rabbitmqctl list_exchanges name type durable 
    sudo rabbitmqctl list_queues name messages arguments 
    sudo rabbitmqctl list_bindings source_name destination_name routing_key

These three commands list all of them. The exchange, queue (including
message count and parameters), binding relationships, and management
interface all display the same information.

## User

Regarding the channel pool provided by the connection, What underlying
data structure should be used? Array + roundrobin?

## Claude

an array with a round-robin interface **is not suitable for** this
scenario. The key reason lies in the assignment requirement: **a channel
can only be used by one thread at a time.**

## The problem of round-robin

Round-robin is a \"round-robin\" allocation method: the first person to
request channel 0 gets channel 0, the second gets channel 1, and so on,
until the last person gets channel 1, at which point the allocation
starts again from the beginning. It only decides \"who gets next\" **and
doesn\'t know if the channel is currently being used by someone else** .

    are 10 in the pool The channel is receiving 50 requests 
    simultaneously :  Request 1 → channel 0
      Request 11 → channel 0 ← Request 1 may not have finished using the channel yet, and both threads are using the same channel simultaneously.

Tomcat has a default maximum of 200 worker threads, and the number of
simultaneous requests can easily exceed the pool size. Even if the
number of requests doesn\'t exceed this limit, if a thread is slower,
the next thread in the queue will encounter it. Multiple threads
publishing messages on the same channel simultaneously will cause frame
interleaving, affecting the channel\'s performance. The issue is either
closed by RabbitMQ or the publisher confirms acknowledgments are
incorrect.

You can add a lock to each channel to remedy this, but this will bring
new problems: when a thread is assigned to a busy channel , it can only
**wait for that channel , and** cannot use other free channels next to
it.

## The correct approach: Use a BlockingQueue for borrowing and returning.

The meaning of \"pool\" should be: **idle channels are placed in the
pool; borrowed channels are no longer in the pool and are returned after
use.** In this way, all channels in the pool will be free, and any one
of them can be used directly.

BlockingQueue embodies this semantics perfectly, and it\'s also the
structure used in the skeleton code provided by the teacher:

    Borrowing: take() → Takes an empty queue. If the channel is fully borrowed, it waits for 
    its return: offer() → puts the channel back into the queue, and waiting threads can immediately get it.

You are already very familiar with it: A1 The producers and consumers in
that story use the same structure, only this time the queue is no longer
filled with\... It\'s not swipe, but channel.

## Code skeleton

    public class ChannelPool {
        private final BlockingQueue <Channel> pool ;
    ​​    private final Connection connection ;

        public ChannelPool ( Connection connection , int size ) throws IOException {
            this connection​ = connection ;
            this.pool​​ = new ArrayBlockingQueue <>( size );
            for ( int i = 0 ; i < size ; i ++) { 
    pool . offer ( connection . createChannel ());   // Pre-create all channels
            }
        }

        public Channel borrowChannel ( long timeoutMs ) throws InterruptedException {
            return pool . poll ( timeoutMs , TimeUnit.MILLISECONDS ) ;​   // Returns null on timeout, preventing indefinite waiting.
        }

        public void returnChannel ( Channel ch ) {
            if ( ch != null && ch . isOpen ()) { 
    pool.offer ( ch ) ;​              // Normal channel: put it back into the pool
            } else {
                // Closed channels cannot be put back into the pool; a new one needs to be created to fill the gap.
            }
        } 
    }

When using:

    Channel ch = pool . borrowChannel ( 1000 ); 
    if ( ch == null ) {
        // If the channel is not borrowed within 1 second: return 503, allowing the client to retry later. 
    } 
    try { 
    ch . basicPublish ( ... ); 
    } finally { 
    pool . returnChannel ( ch );   // Regardless of success or failure, the money must be returned, otherwise the pool will gradually be depleted 
    .

## Several details to pay attention to

**1. Use** `poll(timeout)` **Instead** `take() `**.** `take()` It will
wait indefinitely while the pool is empty. If RabbitMQ slows down, all
Tomcat threads will be stuck here, even\... `/health` Neither of them
can respond, ALB This will determine that the server is unhealthy. Set a
timeout period, and return a value after the timeout. A 503 error can
keep the problem within a certain range.

**2. A note must be written when returning the item.** `finally`
**inside.** If an exception is thrown during a release and the issue is
not resolved\... If a channel is added, the pool will permanently lose
one channel. This will accumulate and eventually deplete the pool.

**3. Check if the channel is still available when returning the item .**
If an error occurs during deployment, RabbitMQ This may have been shut
down. Channels. Returning a closed channel to the pool will cause the
next thread attempting to borrow it to fail. Therefore, a check must be
performed before returning the channel. `isOpen() `; if it has already
been closed, discard it and create a new one to fill the gap, keeping
the pool size constant.

**4. The pool size does not need to be the same as the number of Tomcat
threads.** Publishing a message typically takes very little time, so
channels are borrowed for a short period; a few dozen channels are
sufficient to support 200 Tomcat threads . The specific value is a
parameter for later optimization ( the connection pool size is also
listed in the job\'s Tuning Parameters ). Testing can start from around
20 .

**5. The relationship with publisher confirms .** If you decide to use
publisher confirms, you can call them once **when creating the channel
.** Instead of calling
`` `confirmSelect()` every time you publish, use `confirmSelect()`. This is because confirmation mode is  ``a
channel- level setting, and once enabled, it remains active.

## Other alternative options (for your information only)

- **ThreadLocal\<Channel\>** : Allows each Tomcat worker thread to have
  its own dedicated ThreadLocal \<Channel\>. This eliminates the need
  for borrowing and returning channels, and avoids contention. However,
  the number of channels can be as large as the number of threads (up to
  200 ), and it\'s difficult to uniformly rebuild these channels
  scattered across various threads when a connection is lost and needs
  to be reconnected. The channel is crucial. Most importantly, the
  assignment explicitly requires the implementation of a pool for
  borrowing and returning data.
- **Apache Commons Pool2** : A mature, general-purpose object pooling
  library with built-in features such as idle detection and invalid
  object eviction. It\'s commonly used in practice, but the purpose of
  this assignment is for you to implement your own pool, so its use is
  not recommended.

## User

I don\'t quite understand why using an array would cause multiple
threads to simultaneously access the same channel. What are the
disadvantages of using a lock?

## Claude

This can be viewed using a specific timeline. The key point is that
**the round-robin system is only responsible for \"whose turn it is,\"
and does not know \"who is currently available.\"**

## Why are they getting the same channel?

Assuming there are 3 in the pool Channels are allocated in turn using a
counter:

    Channel[] channels = new Channel[3];
    AtomicInteger next = new AtomicInteger(0);

    Channel get() {
        return channels[next.getAndIncrement() % 3];   // 0, 1, 2, 0, 1, 2, ...
    }

Four requests arrived almost simultaneously :

    Time → 0ms 5ms 10ms 15ms 
    Thread A gets ch0 ─────── Deploying (time elapsed) 20ms) ────────┐Thread 
    B gets ch1 ──Publish complete 
    Thread C gets ch2 ──Publish complete 
    Thread D gets ch0 ← A is still in use ch0!

thread D arrives, the counter has just started spinning back. 0, so it
got it. ch0. But at this point, thread A has not yet finished using it.
ch0. The ch0 in the array is never \"taken away\"; it always stays
there. The counter is just a \"pointer\" that points to whichever
element is allocated.

This can be understood using an analogy: There are 3 restroom stalls,
and the attendant is issuing numbers to people in line in the order of
1, 2, 3 , 1, 2, 3. The fourth person gets number 1 , but the person in
stall 1 hasn\'t come out yet. The attendant only issues numbers in order
and doesn\'t check if anyone is in the stall.

There\'s another easily confusing point here: \[using\...\]
`` `AtomicInteger`  ``only guarantees that **the counter itself** is
thread-safe, meaning two threads won\'t get the same sequence number.
However, it **doesn\'t guarantee that the channel is exclusive** . The
guarantees of \"unique allocated sequence numbers\" and \"only one
thread using this channel at a time\" are not guaranteed. \"channel\" is
two completely different things.

## Locking can solve the correctness problem, but it has three drawbacks.

to each channel , lock it before use, and release it after use:

    Channel ch = channels [ i ]; 
    synchronized ( ch ) { 
    ch . basicPublish ( ... ); 
    }

This does prevent two threads from using the same channel simultaneously
, **and the result is correct** . However, it has the following
drawbacks:

**Disadvantage 1: You will be waiting on a busy channel while the
adjacent channels are idle.** This is the most important issue. Let\'s
continue with the example above:

    Thread D is assigned to ch0 → ch0 is locked by A → D can only wait. 
    At this time, ch1 and ch2 are already idle, but D does not need them.

Round-robin doesn\'t consider channel busyness during allocation . If a
channel happens to be slow (e.g., waiting for RabbitMQ confirmation),
the thread assigned to it will have to wait in the queue, while other
channels remain idle. To use a restroom analogy: you\'re told you can
only use stall number 1 , so you have to queue at stall number 1 , even
if stalls 2 and 3 are empty.

**Disadvantage 2: Damaged channels are difficult to replace safely.** If
ch0 is closed by RabbitMQ due to an error , you need to create a new
channel to replace it. However, at this time, several threads may be
waiting for the lock of ch0 . You need to replace this element in the
array without affecting them, while also ensuring that they use the new
element after acquiring the lock. With channels, this kind of logic
becomes very complex.

**Disadvantage 3: You will eventually reinvent an even worse pool.** To
address the first drawback, you might think: \"Then don\'t just wait,
use\...\" `tryLock` Try each one in turn \"Find an available channel and
use it; if all are busy, wait a while and try again.\" This approach
does solve the problem, but it essentially involves manually
implementing a \"find available channel\" mechanism. It uses a pool that
says \"if there is no channel, wait\", and it\'s less efficient: it has
to scan each channel one by one, and when all of them are busy, it has
to keep polling.

## BlockingQueue have these problems?

    Available channels are in the queue; borrowed channels are no longer in the queue. 

    Thread D comes to borrow... channel:
      There are ch1 and ch2 in the queue → one of them can be retrieved immediately without waiting.
      The queue is empty (all queues have been borrowed) → Waiting, when any channel is returned, it will be immediately awakened and retrieved.

Let\'s use a restroom analogy: Imagine a row of keys hanging by the
door, each key indicating an available stall. You can grab any key and
use it, then hang it back up. If all the keys are gone, they\'re just
waiting at the door; anyone who comes out and hangs the key back up can
get one.

In summary:

  -----------------------------------------------------------------
                    Round-robin     Round-robin +   BlockingQueue
                                    lock            
  ----------------- --------------- --------------- ---------------
  Problems to be    Who\'s next?    Who\'s next,    **Who\'s free
  solved                            and is          right now?**
                                    guaranteed      
                                    exclusive       
                                    rights?         

  Is it possible    meeting         Won\'t          Won\'t
  for two threads                                   
  to use the same                                   
  channel                                           
  simultaneously?                                   

  to wait even when ---             meeting         Won\'t
  there is an                                       
  available channel                                 
  ?                                                 
  -----------------------------------------------------------------

The question we need to answer is \"Who is available now?\", and
BlockingQueue is a data structure designed to answer this question.

## User

So, the \`bq\` contains unused channels, and threads push them into the
\`bq\` after use, right?

## Claude

Yes, that\'s it:

- **BlockingQueue only stores currently free channels.**
- When you need to use a thread, use `poll(timeout)` One **is taken**
  from the queue . At this point, it is no longer in the queue, and
  other threads cannot retrieve it.
- After use, use `offer()` Put it **back in** the queue, and other
  threads can continue to use it.
- If the queue is empty, it means that all channels have been borrowed,
  and threads that want to borrow will wait until someone returns them.

One small detail: In Java \'s BlockingQueue , \"replacement\" uses\...
`offer()` or `` `put()  ``\`, instead of \`put ()\` `push() `. `push()`
This is a method of the Deque. Because our queue capacity is exactly
equal to the total number of channels , there will always be empty slots
when returning items, so we use\... `offer()` That\'s fine.

Two points to emphasize again:

1.  **Returning still needs to be written in** `finally` **It**
    guarantees that it will be returned regardless of success or
    failure.
2.  **Pre-return inspection** `` `isOpen()`  ``. Instead of putting a
    closed channel back in, a new channel is created and placed inside,
    thus keeping the pool size constant.

## User

The server will perform a swipe. Receive the event and send it to
Rabbit. So MQ is just going to ignore it?

## Claude

From a business process perspective, yes. server The responsibility is
to \"hand over the message\" The RabbitMQ process ends there; it
doesn\'t wait for the consumer to finish processing this message. Swipe
doesn\'t care when the leaderboard updates. This is precisely the point
of introducing message queues: **decoupling \"receiving requests\" from
\"processing requests** ,\" the server\... It can respond to clients
quickly, and the processing work can be handed over to subsequent
consumers to complete gradually.

But \"handed over\" When exactly RabbitMQ is considered complete is a
decision you need to make.

## `basicPublish` A return message does not necessarily mean that RabbitMQ has received it.

By default, `basicPublish` The server simply writes the message to its
local network buffer and returns immediately, **without waiting for any
response from RabbitMQ** . If the network connection drops at this
point, or if RabbitMQ experiences a problem, the message will be lost.
Meanwhile, the server has already returned a response to the client. If
the client receives a 201 error, it will assume the request was
successful and will not retry.

Therefore, there are three approaches to choose from:

**Option 1: Send it out and then forget about it (fire-and-forget)**

    ch.basicPublish ( exchange , routingKey , props , body ) ; 
    // Returns 201 immediately

It\'s the fastest, but there\'s a risk of losing messages.

**Option 2: Wait for confirmation for each message (synchronous
publisher confirms).**

    ch.confirmSelect ( ) ;                       // Call ch.basicPublish ( exchange , routingKey , props , body ) 
    once when creating the channel 
    ; ch.waitForConfirmsOrDie ( 5000 ) ;            // Wait for RabbitMQ to confirm receipt; throw an exception if timeout or rejection. 
    // Return 201 only after confirmation; otherwise, return 503.

This is the most reliable approach: the server only responds when
RabbitMQ explicitly confirms receipt. Only then will the client be
notified of success. If it fails, the client will receive\... A 503
error occurs, and then the retry logic written in A1 is followed to
resend the data. The cost is that each deployment requires an additional
network round trip from the server to RabbitMQ . Within the same VPC ,
this takes approximately 1 millisecond.

**Option 3: Batch confirmation or asynchronous confirmation**

One approach is to send multiple messages consecutively and then wait
for confirmation all at once; another is to register a callback function
to asynchronously receive the confirmation result. This approach offers
higher throughput but is far more complex to implement. Furthermore, if
the message is returned before confirmation\... 201 also carries the
risk of losing messages.

## My suggestion

**Let\'s use option two first.** The task objective explicitly states
\"No swipe loss under load,\" and solution two is the only one that
guarantees \"a 201 response means the message has definitely arrived.\"
The approach taken with RabbitMQ is to first ensure correctness and
measure its throughput. Once the optimization phase begins, then
consider whether to switch to Solution 3.

One thing to note when using Option 2 is that **the channel is borrowed
for a longer period** . Previously, only the instant the message was
sent was needed; now, the time for confirmation must be added.
Therefore, more data is required in the pool. The channel value needs to
be determined during optimization through testing.

## Here are two more reliability-related settings.

- **Message persistence** : Used during publishing
  `MessageProperties.PERSISTENT_TEXT_PLAIN `, RabbitMQ Messages will be
  written to disk, ensuring no loss even if RabbitMQ restarts. This
  requires use with durable queues. The trade-off is that it will be
  slightly slower.
- **mandatory flag** : `basicPublish` There is one parameter
  `mandatory `. When set to true , RabbitMQ will disable routing if a
  message cannot be routed to any queue (e.g., the routing key is
  incorrect). Instead of silently discarding the message, it will return
  it to you. This setting is very helpful during the debugging phase.

## A thought that can be written into the design document

Strictly speaking, server When a 201 error is returned , the swipe has
not yet **been processed** ; it has only been \"accepted.\" (HTTP) There
is actually a more accurate status code: **202 Accepted** , which means
\"the request has been accepted, but has not yet been processed.\" This
is the standard semantics of asynchronous processing architecture.

However, your A1 client uses 201 to determine success or failure, and
the job doesn\'t require changing the status code, so continuing to
return 201 is perfectly reasonable. You can mention this difference in
your design document to show that you understand the semantic difference
between synchronous and asynchronous processing.

## User

What does the \`confirmSelect\` method mean? Please explain.

## Claude

`confirmSelect()` Its function is **to switch this channel to \"publish
confirmation\" mode** (publisher). Once enabled, every message you
publish through this channel will be confirmed by RabbitMQ. They will
reply with a confirmation, telling you, \"I have received this message
and have already taken care of it.\"

## Using the analogy of sending letters

- **Not confirming** is like dropping a letter in the mailbox and
  leaving. You have no idea whether the post office has accepted the
  letter or whether it will get lost in transit.
- **The \"confirm\" option has been activated** , similar to sending a
  registered letter. Once the post office receives the letter, they will
  give you a receipt. Only with this receipt can you confirm that the
  post office has taken responsibility for the letter.

## What exactly happened after it was turned on?

    ch.confirmSelect(); // Tell RabbitMQ: Please send me a receipt for all messages sent on this 

    channel . ch.basicPublish(...); // First message, RabbitMQ Internally, it is numbered 1. 
    `ch.basicPublish(...);` // Message 2 , numbered 2. `RabbitMQ → ack(1)` // Acknowledgment: Message 1 has been received. 
    ` 

    RabbitMQ → ack(2)` // Acknowledgment: Message 2 has been received .

- **Numbering** : After confirming is enabled , every message published
  on this channel will be numbered sequentially, starting from 1. The
  numbering is channel -level; different channels count independently.
  (This can be done using\...) `ch.getNextPublishSeqNo()` Check the
  message number.
- **\`ack\`** indicates that RabbitMQ has taken over the message. For
  persistent messages sent to the durable queue, RabbitMQ\... It will
  wait until the message **is written to disk before** sending. ack. So
  I got it. An ack means that the message will not be lost even if
  RabbitMQ restarts immediately.
- **\`nack\`** : This indicates that RabbitMQ failed to receive the
  message due to an internal error. This situation is rare, but if it
  occurs, you\'ll know you need to retry.

## How to receive receipts

`confirmSelect()` Simply enabling this mode requires calling a separate
method to receive receipts; there are two ways to do this:

**Synchronous waiting** (Option 2 recommended in the previous round):

    ch . basicPublish ( ... ); 
    ch . waitForConfirmsOrDie ( 5000 );   // Block, wait for acknowledgment 
    // ACK received → Method returns normally 
    // NACK received or no acknowledgment received within 5 seconds → Throw exception

There is another similar method `` `waitForConfirms()  ``\` returns a
boolean instead of throwing an exception. `OrDie` The version is better
because it throws an exception directly on failure, so you won\'t forget
to handle it.

It is important to note that `waitForConfirms` What\'s being waited for
is **all the unacknowledged messages on this channel** , not just the
last one. However, in your design, a channel is only borrowed by one
thread at a time, publishing a message and then waiting for
confirmation, so what\'s being waited for is exactly the message that
was just published.

**Asynchronous callback** (Option 3):

    ch.addConfirmListener (
    ​​    ( seqNo , multiple ) -> { /* Received ACK */ },
        ( seqNo , multiple ) -> { /* Received ACK */ } 
    );

After publishing, there\'s no need to wait; the callback function will
be automatically invoked when the receipt arrives `.` When true , it
means \" all messages with a number less than or equal to seqNo have
been acknowledged\" in RabbitMQ. Sometimes, batch confirmation is used
to improve efficiency.

## Several rules you need to know

1.  **Each channel only needs to be called once.** Once started, this
    channel will remain in confirmed mode and **cannot be closed** .
    Therefore, the best time to call it is when the ChannelPool creates
    the channel , rather than every time it is published.
2.  **It cannot be used simultaneously with transaction mode.** RabbitMQ
    also has an older reliability mechanism called transactions (
    `txSelect `), which is much slower than confirm . Only one of these
    methods can be selected within the same channel .
3.  **Why is it not enabled by default?** Because RabbitMQ needs to
    record the status of each message and send an acknowledgment, this
    incurs additional overhead. For scenarios where losing a few
    messages is insignificant (such as logs or monitoring data), it\'s
    unnecessary to enable this feature. However, your swipe requires
    \"no data loss,\" so it needs to be enabled.

## User

Does the teacher mean that the vertical for each lift is placed in
lift_vertical.json, and the program automatically loads it into memory?

## Claude

Yes, that\'s the most reasonable understanding: **the file is read into
memory once when the program starts, and then each subsequent request
directly looks up the table in memory** , instead of reading the file
every time a swipe is processed . However, I can\'t see the specific
content of this file in the starter repo . The code below is written in
a common format; you need to adjust the parsing part according to your
actual format.

## Why preload?

- **Speed** : Reading files is done via disk I/O, where each request
  requires a read, severely slows down throughput. In contrast, memory
  table lookups only take a few nanoseconds.
- **The data will not change** : 40 The vertical of each lift is fixed
  and will not change during runtime, so loading it once is sufficient.
- **Thread safety : This table is read-only** after it is loaded , and
  multiple Tomcat threads can read it simultaneously without any
  problems. No locking is required, and ConcurrentHashMap is not needed.

## What data structure should be used to store\...

The liftID range is 1 to Since 40 is a consecutive integer, **using an
array is the most suitable approach** .

    int [] verticals = new int [ 41 ];   // Subscript 0 is not needed; verticals[21] is the vertical of lift 21.

The query is `verticals[liftID] `is faster and more memory-efficient
than HashMap .

## Where is the file located, and how do I access it?

Put the file in `src/main/resources/` In the directory. During
packaging, Maven\... It will be put into WAR . `WEB-INF/classes/`
Directory. Reading requires\... **classpath** Read it, not by file path:

    // Correct: Read 
    InputStream from classpath in = getClass (). getClassLoader (). getResourceAsStream ( "lift_vertical.json" ); // Error: reading 

    new class by file path
     File ( "lift_vertical.json" );

The file path method might work by chance when running in an IDE , but
it will fail to find the file after deployment to Tomcat . This is
because the program\'s working directory is now Tomcat \'s. `bin` The
directory, not your project directory.

## Loading code

Assuming the file format is `{"1": 120, "2": 85, ...} `, parsed using
Gson :

    public static int[] loadVerticals() {
        InputStream in = LiftVerticals.class.getClassLoader().getResourceAsStream("lift_vertical.json");
        if (in == null) throw new IllegalStateException("lift_vertical.json not found on classpath");

        Map<String, Integer> raw = new Gson().fromJson(
                new InputStreamReader(in, StandardCharsets.UTF_8),
                new TypeToken<Map<String, Integer>>() {}.getType());

        int[] verticals = new int[41];
        for (int lift = 1; lift <= 40; lift++) {
            Integer v = raw.get(String.valueOf(lift));
            if (v == null) throw new IllegalStateException("missing vertical for lift " + lift);
            verticals[lift] = v;
        }
        return verticals;
    }

If the file is in another format, such as
`[{"liftID": 1, "vertical": 120}, ...]` For such an array, only the
middle parsing part needs to be modified; the logic for filling the
array remains unchanged.

## Where to call

to RabbitMQ connections, they are loaded once when the application
starts:

- Place `ServletContextListener` of `contextInitialized` Once loaded,
  save it to\... ServletContext; or
- Placed in SkierServlet `init()` In the method, store it as a `final`
  Member variables.

Both methods are acceptable. The validation in the code is intentional:
**if a file is missing or the data is incomplete, it will fail directly
at startup** , rather than waiting until a request cannot find the
vertical . This is the same principle as the parameter validation we
perform in the SwipeProducer constructor, called \"fail fast\".

If your file format is different from the example above, paste the first
few lines of your file to me, and I\'ll adjust the parsing code for you.

## User

I\'m a little confused. Does the teacher mean that the lift for each
resort follows that data? Does that mean that each resort is now using
the same lift?

## Claude

According to the assignment description, yes. The original text is:
*\"each of the 40 lifts has a fixed vertical drop in the 60--400 ft
range* ,\" meaning\... **40​ Each lift has a fixed vertical axis** . Only
one is mentioned here. liftID was not mentioned. Since this table is for
resorts, it should be a shared table for queries based on liftID ,
accessible across all resorts . That is, lift 21 in resort 1 and lift 21
in resort 7 , vertical\... They are the same.

This is obviously unrealistic, as every ski resort has different
chairlifts in reality. However, this is a simplification made for
simulation purposes, and it\'s consistent with the data generation
method of A1 : A1 The liftID in the context is randomly generated
between 1 and 40 and has no relation to the resortID . This assignment
focuses on the architecture of message queues, aggregation, and push,
rather than simulating real ski resort data, so this simplification is
reasonable.

However, the final determination should be based on **the structure of
the file itself** ; you can confirm this by simply opening it and taking
a look.

- If the file contains only 40 records, each containing only liftID and
  Vertical means that all resorts share a single table, and so on.
  `verticals[liftID]` You can find it by searching.
- If the files are grouped by resort , for example
  `{"1": {"1": 120, "2": 85, ...}, "2": {...}} `, or each record
  contains\... \`resortID\` means each resort has its own table, and
  queries need to be performed using the values (\`resortID\`,
  \`liftID\`). The data structure also needs to be changed to a
  two-dimensional array. `int[11][41] `.

If you\'re still unsure after reviewing the documentation, you can ask
your instructor or teaching assistant on the course forum. For your
design, the only difference between these two scenarios lies in whether
you use one or two keys when looking up the table. The key remains
completely unaffected; the rest is entirely unaffected.

## User

How to generate a UUID in Java?

## Claude

use `java.util.UUID` A class is sufficient, requiring only one line of
code:

    String eventId = UUID.randomUUID ( ). toString (); 
    // For example: 3f2b8c1e-9a4d-4e7b-b1c2-5d6e7f8a9b0c

- `randomUUID()` The generated version is the 4th edition . UUIDs have
  122 random bits out of their 128 bits , so the probability of
  generating the same value twice is negligible.
- `toString()` It will be converted into a standard 36 -character
  format, that is, an 8-4-4-4-12 -digit hexadecimal number, separated by
  hyphens.
- It is **thread-safe** , and multiple Tomcat threads can call it
  simultaneously without any additional processing.

It is used internally. `SecureRandom` To generate random numbers. At a
call frequency of several thousand times per second, performance is
perfectly fine.

## A question worth considering: eventId Where is it generated?

According to the message format of the task, eventId It should be **This
is generated when the server receives the swipe** . Doing it this way
prevents duplicates from being generated. **RabbitMQ duplicate
delivery** scenario: consumer RabbitMQ crashed after processing the
message but before it could be acknowledged (ACKed) . The message will
be re-delivered, and since the eventId is the same, it can be identified
by deduplication.

However, there is one situation it cannot prevent: **client retry** .

    The client sends a swipe → the server generates an eventId = A and publishes it successfully 
    to RabbitMQ → en route to receiving a 201 error, a network timeout occurs and 
    the client does not receive a response, so it retryes → the server generates a new eventId = B and publishes it again. 
    Result: The same swipe enters the queue twice, with different eventIds. The difference is that deduplication cannot identify duplicates → vertical values are counted repeatedly.

To prevent this, ID It must be generated on **the client side** , and
the same value must be carried with each retry. ID. This is what the
industry commonly refers to as an idempotent **key .** key): The client
includes a key in the request header. ID, server Use it directly as
Instead of generating a new eventId, use eventId.

Regarding this assignment:

- The message format and scoring criteria for the job are designed for
  RabbitMQ\'s duplicate delivery scenarios, so generating an eventId on
  the server side is acceptable.
- In actual testing, duplicates caused by client retries only occur when
  network timeouts, and the number of times is very small.
- However, this is a good point for design discussion. You can explain
  in the design document that \"the deduplication scope only covers
  duplicate deliveries from the broker ,\" and then propose using
  client-side idempotent keys as an improvement. This is also good
  material for reflecting on AI usage, highlighting \"areas where you
  need to make your own reasoning decisions.\"

## User

How to set 201 and 503 in the response respectively?

## Claude

In a servlet , status codes are\... `HttpServletResponse` of
`setStatus()` Method settings:

    response.setStatus(HttpServletResponse.SC_CREATED);               // 201
    response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);   // 503

`SC_CREATED` and `SC_SERVICE_UNAVAILABLE` yes `HttpServletResponse` The
predefined constants have values of 201 and 201 respectively. 503. You
can also write it directly. `setStatus(201) `is better, but using a
constant is more readable.

## Complete process in doPost

    // Validation failed → 400 (Logic already written in A1) 

    Channel ch = null ; 
    try { 
    ch = channelPool . borrowChannel ( 1000 );
        if ( ch == null ) {                                  // No channel borrowed within 1 second
            sendJson ( response , ...) 503 , "{ \" message \" : \" server busy, retry later \" }" );
            return ;
        } 
    ch . basicPublish ( "skitrak.exchange" , "resort." + resortID , 
    MessageProperties . PERSISTENT_TEXT_PLAIN , body ); 
    ch . waitForConfirmsOrDie ( 5000 );                     // Waiting for RabbitMQ confirmation

        sendJson ( response , ...) 201 , "{ \" message \" : \" swipe accepted \" }" ); 

    } catch ( Exception e ) {                                // Publish failed or confirmation timed out
        sendJson ( response , ...) 503 , "{ \" message \" : \" queue unavailable, retry later \" }" ); 
    } finally { 
    channelPool . returnChannel ( ch ); 
    }
    private void sendJson(HttpServletResponse res, int status, String json) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write(json);
    }

Here `catch (Exception e)` To keep the example concise, in actual code
it\'s best to catch specific exception types and log them.
`InterruptedException` This should also be handled separately; remember
to restore the interrupt flag.

## A few details

**1. Set the status code first, then write it to the body.** Once you
start towards `getWriter()` Once enough content is written and a buffer
flush is triggered, the response is \"committed.\" The status line and
response headers are sent first, followed by the call to\...
`setStatus()` It will no longer take effect. Therefore, the order should
always be: status code → response header → body.

**2. 503 can be added.** `Retry-After` **The response header** tells the
client how long to wait before retrying:

    response . setHeader ( "Retry-After" , "1" );   // The unit is seconds

This is the HTTP response header used in conjunction with 503. Your A1
client will already use exponential backoff and retries when it
encounters 5xx , so this header is not strictly necessary, but adding it
makes the API more standardized.

**3. Do not use** `sendError() `**.** You might see it online
`response.sendError(503, "...")` This is how it\'s written. It can also
set a status code, but Tomcat will replace the response body with a
default one. **HTML error page** . Your API is returning\... JSON, so
use `setStatus()` Adding your own way of writing to the body is more
appropriate.

**4. Why is it? 503, not 500?** 500 Internal Server Error means \"There
is an internal problem with the server\". \"Bug\"; 503 Service
Unavailable means \"The server is temporarily unable to process this;
try again later, it may succeed.\" In this case, RabbitMQ is temporarily
unavailable, or the channels are temporarily occupied, which is a
temporary problem, so 503 is more accurate. This distinction also has
practical significance for the client: your A1 client will retry 5xx
errors, and the 503 return here will trigger a retry. Once RabbitMQ
recovers, the swipe can be successfully published without loss.

## User

Could you help me organize each swipe sent from the server to Rabbit?
What is the process of MQ and what needs to be done?

## Claude

It can be divided into two parts: **the preparation work that is done
only once at startup** , and **the process that is repeated for each
swipe** .

## 1. At startup (ServletContextListener, executed only once)

    ① Load lift_vertical.json → int[] verticals 
    ② Generate the local serverId 
    ③ Create a RabbitMQ Connection 
    ④ Declare the topology: exchange, 10 ⑤ Creation of queues, binding relationships, dead-letter exchanges , and dead-letter queues
     ChannelPool: Pre-create N channels For each channel , confirmSelect() 
    is called once. ⑥ Store verticals, serverId, and channelPool into... ServletContext is used by SkierServlet .

about **\`serverId\`** : This identifies which server published the
message. You can use the hostname (\`
`` INETAddress.getLocalHost().getHostName()`  ``), or pass a name via an
environment variable, such as\... `server-1 `. It will be very useful in
Part 3 : by counting the number of messages with different serverIds ,
you can directly verify whether ALB has evenly distributed requests to
each server.

## II. Processing flow for each swipe (doPost)

    Request arrived│ 
    ├─ 
    1. Parsing and validating the URL path ──────────── Failed → 400 
    ├─ 2. Parsing and validating the JSON body ──────────── Failed → 400 
    │ 
    ├─ 3. Lookup table: vertical = verticals[liftID] 
    ├─ 4. Assemble the message 
    ├─ 5. Serialize into a JSON byte array 
    │ 
    ├─ 6. Borrow channel (with timeout) ──────────── Timeout → 503 
    ├─ 7. Publish to `skitrak.exchange`, routing key is `resort.{resortID} 
    ` ├─ 8. Wait for RabbitMQ confirmation ──────────── Failure or timeout → 503 
    ├─ 9. Return 201 
    │ 
    └─ finally: Return (If the channel is already closed, switch to a new one)

### Steps 1 and 2 : Verification

This part is exactly the same as A1 ; simply reuse the code you\'ve
already written. **Validation must be placed at the very beginning** ;
invalid requests should be returned immediately. I would never borrow
400. The channel will not consume any RabbitMQ resources.

### Steps 3 and 4 : Look up the table and assemble the message

The sources of each field are as follows:

  -------------------------------------------------------------------------------
  Fields                         source
  ------------------------------ ------------------------------------------------
  `eventId`                      `` `UUID.randomUUID().toString()`  ``generates a
                                 new string for each swipe .

  `resortID `, `dayID `,         Parsed from the URL path
  `skierID`                      

  `liftID `, `time`              Parsed from the request body

  `vertical`                     `verticals[liftID] `is obtained by looking up a
                                 table.

  `timestamp`                    `Instant.now().toString() `, server Time of
                                 receiving the request

  `serverId`                     This value is generated at startup and is used
                                 by all requests.
  -------------------------------------------------------------------------------

**Pay attention to the field types specified in the job instructions.**
`resortID `, `dayID `, `skierID `, `liftID `, `serverId` All are
**strings** (JSON) (with quotes) Only `vertical` and `time` It\'s **a
number** . If you define a message class and then use Gson for
serialization, Java\... The data type of the field must be consistent
with this; otherwise, problems will occur when the consumer parses it.

The seasonID is not included in the message format. If needed, you can
confirm it with your teacher or specify it in the design document.

### Step 5 : Serialization

    byte [] body = gson . toJson ( message ) . getBytes ( StandardCharsets . UTF_8 );

`Gson` The object is thread-safe. You can create one in the servlet and
use it as a member variable for all requests to share. There is no need
to create a new one for each request.

### No. 6～9 Step: Publish

This is the code I wrote in the previous round: using a channel →
`basicPublish `(using persistent messages) → `waitForConfirmsOrDie `→
Return 201. Return if any step fails. 503, prompting the client to
retry.

### finally: Return the channel

Whether you succeed or fail, you must return it. If the channel is
already closed (which can happen if a publishing error occurs), discard
it and create a new one to put back into the pool, keeping the pool size
unchanged.

## III. When closed (contextDestroyed)

    Close all channels in ChannelPool → Close Connection

## Areas not yet covered: Connection disconnected

The above process handles the case where publishing a single message
fails. However, if the **entire** RabbitMQ connection is lost (e.g.,
RabbitMQ restarts), all channels in the pool will become invalid. The
job requires handling this situation.

`amqp-client `5.x has automatic **recovery** enabled by default .
Recovery function: After a connection is lost, it will automatically
reconnect and rebuild the original connection. However, there are some
interaction issues to be aware of between this feature and your own
ChannelPool : for example, whether channels borrowed from the pool are
available during recovery, and when returning them. `isOpen()` What will
the inspection results be? This part is rather complex, and it is
recommended to discuss it separately after the release process is
running smoothly.

## User

Doesn\'t a mature tool like RabbitMQ have a reconnection mechanism after
a disconnection?

## Claude

Yes. RabbitMQ Starting with version 4.0 , the Java client has automatic
**recovery enabled by default .** The recovery function. The part you
asked earlier that needed to be \"discussed separately\" mainly revolves
around this.

## What can auto-recovery do?

After the connection is lost, the client will automatically perform
three tasks:

1.  **Reconnect** : Attempts to reconnect at regular intervals, with a
    default interval of 5 seconds.
2.  **Channel restoration** : The original Channel object is not
    discarded; after the connection is restored, **the same object**
    will become available again. This is because\... `createChannel()`
    What was returned was actually a packaged one.
    `AutorecoveringChannel `.
3.  **Restore topology** : Automatically redeclare connections that were
    previously declared on this connection. Exchange, queue, binding
    relationship, and consumer.

Related configurations:

    factory .setAutomaticRecoveryEnabled ( true ) ;    // The default value is true 
    . factory.setNetworkRecoveryInterval ( 2000 ) ;     // Reconnection interval in milliseconds 
    . factory.setTopologyRecoveryEnabled ( true ) ;     // Whether to restore topology, defaults to true

## Problems it cannot solve

Automatic recovery is responsible for \"reconnecting after a connection
is lost,\" but there are a few things it can\'t handle and you still
need to deal with them yourself:

**1. Requests during recovery.** There is a gap of a few seconds between
the disconnection and reconnection. During this period, calls are made.
`basicPublish` Will throw `An AlreadyClosedException will occur `. You
need to catch this exception and return. 503, tell the client to try
again later.

**2. The first connection fails upon startup.** Automatic recovery only
works after **a successful connection has been established . If**
RabbitMQ happens to be unavailable when Tomcat starts ,
`` `newConnection()` will not work. `` It will throw an exception
directly, and automatic recovery will not intervene at all. Therefore,
you need to write your own retry logic for the connection at startup.

**3. I did not receive a confirmation message at the moment of
disconnection.** If a message has been sent but the connection is broken
before an ACK is received, `waitForConfirmsOrDie` This will throw an
exception. At this point, you have no way of knowing whether RabbitMQ
has actually received the message; you can only return an exception. A
503 error prompts the client to retry. If RabbitMQ has already received
the message, a duplicate message will be generated. This is one of the
reasons why the consumer needs to deduplicate by eventId ; the two
mechanisms work together here.

**4. A single channel was closed due to an error.** If a channel is
closed by RabbitMQ due to a protocol error (such as the 406
`PRECONDITION_FAILED error `mentioned earlier ), but the connection
itself is still normal, then automatic recovery **will not** restore
this channel. The channel only activates when the connection fails. In
this case, the ChannelPool\... You still need to create a new channel to
replace it.

## I need to correct one of my previous suggestions.

When discussing ChannelPool , I suggest you check when returning a
channel . `` `isOpen()  ``\` discards the closed application if it has
already been closed and creates a new one. **This approach can cause
problems when automatic recovery is enabled** .

    RabbitMQ disconnects → all channel isOpen () calls return false 
    → every returned channel is discarded as a broken 
    channel → attempts to create a new channel → but the connection is also broken at this point, and creation fails 
    → the pool is gradually emptied.

In reality, these discarded The channel was supposed to be automatically
restored after a few seconds.

A more accurate way to determine this is **to distinguish between \"the
connection is broken\" and \"only the channel is broken\".**

    if (! ch . isOpen () && connection . isOpen ()) {
        // The connection is normal, but this channel is closed → Discard it and create a new one 
    . else {
        // Connection lost (recovering), or channel itself is normal → Return to pool, wait for automatic recovery 
    }

This is a real-world example you could write into your reflections on AI
usage: AI An initial suggestion was given that was correct in simple
cases but conflicted with another function of the library; it was later
discovered and corrected during the discussion.

## Key question: Does the assignment require you to implement the reconnection yourself?

Let\'s look at this sentence in the assignment again:

> *"with RabbitMQ you implement connection and channel pooling and
> reconnection yourself, which is the whole point of this assignment"*

This statement explicitly states that **implementing reconnection
manually** is one of the key aspects of this assignment. Therefore,
relying entirely on the library\'s automatic recovery may not fully
align with the assignment\'s intent. You have two options:

**plan A: Use the library\'s automatic recovery to handle the parts it
doesn\'t cover.** In other words, the four points listed above: Return
during recovery 503 retries at startup, handling of unacknowledged
messages, and replacement of broken channels . This approach involves
less code and is the most common practice in real-world scenarios.
However, you need to clearly specify in your design document which
functions are handled by the library and which you implement yourself.

**plan B: Turn off automatic recovery and implement it entirely
manually.** set up `` `setAutomaticRecoveryEnabled(false) `  ``registers
an automatic recovery on the connection .
`The ShutdownListener `receives a notification when the connection is
broken, then re-establishes the connection using exponential backoff and
rebuilds the entire system. ChannelPool. This approach requires more
code, but it perfectly meets the assignment\'s literal requirements, and
you can clearly explain the design of each step.

My suggestion is to **first ask the teacher on the course forum whether
using the library\'s built-in automatic recovery is acceptable.** If you
don\'t get a clear answer, option B is safer, because the original
assignment already explains it very clearly. Furthermore, you \'ve
already implemented WebSocket reconnection after disconnection in A1 ,
and the exponential backoff and state management approaches are exactly
the same.

Regardless of which option is chosen, **a final practical test is
necessary** : during client operation, SSH\... Execute on the machine
where RabbitMQ is located
`` Run `sudo systemctl restart rabbitmq-server`  ``, then observe: A
small number of \[unclear\] instances appear within a short period of
time. The client retried successfully after receiving a 503 error. Once
the connection was restored, the throughput returned to normal, and the
final total number of swipes was not lost.

## User

We\'ll build the framework first, then handle the index backoff and
reconnection problem. Let me explain my understanding of the consumer.
The consumer needs to retrieve all swipes from the broker, parse them
into structured objects, check the UUID to ensure idempotency, then
perform daily vertical accumulation for different skiers, update and
maintain the ranking table, push the updated ranking table to the
broker, and then the broker passes it to the websocket. My questions
are: 1. How should we consider the thread architecture for consumers? Is
multithreading necessary? How many threads are appropriate? 2. What data
structure should be used to store the information of different skiers at
this stage? Is the database part of assignment 3?

## Claude

Your understanding is generally correct. Let me correct two minor points
first, and then answer your two questions.

## Two minor corrections

**1. Not \"Retrieve All\" Instead of a \"swipe\", it\'s a subscription
queue that is continuously pushed by the broker .** The consumer
calls\... `basicConsume` After subscribing to a queue, RabbitMQ It will
proactively push messages to the consumer. Process each message
received. The push speed is\... **prefetch** Parameter control: It
indicates the maximum number of messages that the consumer can hold
simultaneously that have not yet been acknowledged .

**2. The ack should be placed last.** The correct order is: parsing →
deduplication check → accumulating vertical values → updating rankings →
(If the ranking changes) Post to backplane → **ack** . Only after all
processing is complete. ACK, this is the \"at least one delivery\"
requirement of the assignment: if the consumer crashes midway, the
message has not yet been delivered. ack, RabbitMQ It will then be
redelivered to another consumer.

## question 1: Consumer\'s thread architecture

### First, understand how the RabbitMQ client allocates threads.

In the RabbitMQ Java client, you **don\'t need to create your own
thread** to continuously fetch messages. What you do is: call \[the
appropriate function\] on a specific channel . Use
`` `basicConsume`  ``to register a callback function. Then, for each
incoming message, the client will invoke your callback within its
internal thread pool.

One crucial rule is that callbacks for messages on **the same channel
are executed sequentially, one after another** , and not concurrently.
Therefore, \"one consumer thread\" here actually corresponds to **one
channel** .

### The number of threads is limited by the order requirement.

The task requires that messages from the same resort be processed
sequentially. The most direct way to implement this is to have **each
resort queue consumed by only one channel** .

    Channel 1 – Consumes Resort.1; 
    Channel 2 – Consumes Resort.2 
    ; ... 
    Channel 10 – Consumes Resort.10

Thus, 10 Each resort can be processed in parallel by 10 threads, while
within each resort , the processing is still strictly sequential.

If you configure the number of channels to K(K) Less than 10), then
divide the resort equally among them: resort i is assigned to the
first\... `i % K` indivual This is what the assignment meant by \"each
consumer is responsible for a part \". \"Resort, and distribute
evenly.\"

The problem is that once **K exceeds 10 , the extra threads have nothing
to do** because there are only 10 queues. This is the contradiction we
mentioned earlier: the job requires testing 10, 20, 40 , and 80 consumer
threads. This issue can be addressed during the tuning phase, but here
are a few ideas:

- **To put it simply : Under** the premise of strictly maintaining the
  order within the resort , having more than 10 threads does not
  increase parallelism, and then using test data to prove this point.
  This in itself is a valuable analysis.
- **Finer-grained partitioning** : For example, splitting each resort
  into several sub-queues based on the hash value of the skierID . This
  way, messages within the same skier remain ordered, and vertical
  accumulation only needs to maintain order within the same skier .
  However, the trade-off is that the leaderboard for the same resort
  will be updated simultaneously by multiple threads, requiring
  additional synchronization.
- **In the split processing phase** : the consumer thread is only
  responsible for receiving and deduplicating data, while the
  computation is handled by another group of threads. This makes the
  process much more complex.

### How many threads are actually needed?

The computational cost of aggregation is very small: one update HashMap,
check it. For the top-N messages, each message only takes a few
microseconds. Therefore, **a single thread can process tens of thousands
of messages per second** . The real bottleneck for the consumer is
usually not in computation, but rather in:

- **the prefetch setting is too small** : prefetch=1, consumer After
  processing each message, you have to wait for RabbitMQ to send the
  next one, which adds the time of a network round trip, resulting in
  very low throughput. It\'s typically set to 100 to 500.
- **Too many ACKs** : Acknowledging each message individually adds an
  extra network communication step. Batch ACKs can be used instead. ack:
  `basicAck(tag, true)` The second parameter `` `multiple=true`  ``means
  confirming this number and all previous messages at once.

So, **start with 10 . One channel per resort** , and making K and
prefetch configurable parameters is sufficient. Adjustments can be made
based on test results during optimization.

## question 2: Data Structure

Yes, this assignment is entirely **stored in memory** ; persisting it to
the database is the task of Assignment 3 .

### skier \'s cumulative vertical

    ConcurrentHashMap < String , Long > skierVertical ;   // key = "resortID:dayID:skierID" 
    skierVertical . merge ( key , vertical , Long :: sum );   // Atomic accumulation, returning the new accumulated value.

`merge` It\'s an atomic operation: if the key doesn\'t exist, then add
it. \`vertical\`; if it already exists, add the old value to
\`vertical\` . It will return the sum, which is exactly the value you
need when you update the ranking in the next step.

Here\'s a further question to consider: if each resort is handled by
only **one thread** , then all the skier data for that resort will
actually only be accessed by that single thread. In this case, you could
use a regular thread for each resort . HashMap, this is what was
discussed in A1 regarding \"thread confinement\". The assignment
provided is\... ConcurrentHashMap is perfectly fine to use, but you need
to understand why it is safe and in what situations you don\'t actually
need it.

### Top-N of each resort

Here is a very useful property: **vertical only increases, it never
decreases.** therefore:

- One that **is already in** the top- N Skiers can only rise; they can
  never be pushed out.
- One **that is not in** the top- N Skier can only enter the top-N if
  his new total **exceeds the minimum value in the current top-N** .

to sort all the skiers at all; you only need to maintain a small list of
size N.

    New total received from Skier X total: 
    X is already in the list → update its value and reorder → the ranking may have changed , 
    X is no longer in the list, and the list is not full → add it directly and sort; 
    X is not in the list, and total > the minimum value in the list → replace the one with the minimum value. skier, sorting
      Otherwise → No change in ranking, do nothing.

N equals 10 , the overhead of searching and sorting among 10 elements is
negligible. A simple ArrayList is sufficient; a TreeMap or similar
approach is unnecessary. PriorityQueue is particularly unsuitable
because it cannot efficiently update the value of an element that **is
already in the queue** .

### Deduplication set

    Set <String> seenEvents =​​ ConcurrentHashMap . newKeySet ();

Pay attention to memory usage. 100 Tens of thousands of UUID strings,
each with the overhead of a hash table, would each occupy approximately
one hundred bytes, totaling 100 to 150 MB. The t2.micro only has 1 GB of
memory, posing a real risk.

A common compromise is **to only remember the most recent batch of
eventIds** . This is because duplicate deliveries in RabbitMQ typically
occur within a short period (e.g., immediately after a consumer crash,
RabbitMQ\...). (It will be resent). Messages from a long time ago are
generally not resent. You can use\... `LinkedHashMap` Cooperate
`removeEldestEntry` Implement a fixed-size LRU set, for example,
retaining the most recent 100,000 messages per resort . The trade-off is
that if a message is redelivered much later, it may become undetectable.
This trade-off can be documented in the design document.

**Also pay attention to the timing of deduplication** : first check if
it has already been seen → if it has, directly ack and skip; if it
hasn\'t been seen, complete the aggregation first, **then mark** the
eventId as processed, and finally\... ack. If the data is recorded first
and then aggregated, and an error occurs during the aggregation process,
the message will be permanently treated as \"processed,\" resulting in
data loss.

### counter

    LongAdder swipesProcessed ;

to count the total number of processes processed, for monitoring and
calculating the consumer \'s processing speed.

### One thing to note: resortSubscribers Not in consumer

The data structures listed in the assignment include
`resortSubscribers `( a collection of WebSocket sessions for each resort
). However, in the backplane scheme, **the consumer does not hold any
WebSocket connections** ; it is only responsible for publishing the
leaderboard to\... The backplane is the actual holder of the session .
Since it\'s server-v2, this structure should be placed in the
LeaderboardEndpoint of server-v2 . You \'ve already written this Map in
A1 .

The assignment lists the four structures together because they represent
the states required by **the entire system** , and there\'s no
requirement to place them in the consumer . This also reflects the
result of your choice of the backplane approach: the states are divided
into two parts, and the computation of relevant states is done in\...
The consumer\'s connection-related state is in This should be included
in the design document.

## User

If our consumer cannot parse a message, how do we notify the broker of
the error? How many times should the broker try before entering the
deadletter queue?

## Claude

## 1. How to notify broker: nack, and not re-queue.

In the consumer \'s callback, through `basicNack` or `basicReject` Tell
RabbitMQ that this message processing failed:

    channel . basicNack ( deliveryTag , false , false ); 
    // ↑ ↑ 
    // multiple=false requeue=false

- **deliveryTag** : The message\'s ID on this channel , derived from the
  callback parameter. `envelope.getDeliveryTag()` Obtained from \[the
  source\].
- **multiple=false** : Only reject this message.
- **\`requeue=false\`** : Do not put it back into the queue. This is
  because the business queue is configured\...
  `x-dead-letter-exchange `, so this message will be forwarded to\...
  `skitrak.dlx `, eventually enters `skitrak.dlq `.

if `When requeue=true `, the message will be put back into the original
queue and then redelivered.

`basicReject(deliveryTag, false)` The function is exactly the same,
except that it does not support the multiple parameter and can only
reject one at a time.

## 2. How many times will the broker try? The answer is: It\'s up to you.

This is important: **for regular classic queues, RabbitMQ It does not
count the number of retries, nor does it automatically convert messages
into dead letters after a certain number of retries.**

- you `requeue=true` If it rejects you once, it will redeliver the
  message. If you keep rejecting it like this, it will keep redelivering
  the message **indefinitely .**
- Only when you use `requeue=false` A message will only enter the dead
  letter queue if it is rejected.

Therefore, the logic of \"entering the dead-letter queue after several
attempts\" needs to **be implemented by you in the consumer** . RabbitMQ
only sets a queue when a message is redelivered. `redelivered` The flag
( `envelope.isRedeliver() `) tells you \"this message has been delivered
before\", but it doesn\'t tell you exactly how many times.

If you want RabbitMQ to count the number of occurrences, you can use\...
Set up **a quorum queue** and configure it. `x-delivery-limit`
Parameters, such as 3. After this number, RabbitMQ\... Messages will be
automatically converted to dead letters. RabbitMQ quorum queues in
versions 4.0 and above have a default limit on the number of deliveries.
However, quorum queues need to be declared again (by setting\...).
`(x-queue-type: quorum `), and its performance characteristics are
different from those of a classic queue. For this assignment, using a
classic queue and handling it within the consumer is sufficient.

## 3. The key is to distinguish between two types of errors.

This is the same principle as distinguishing between 4xx and 5xx in A1 :

  ---------------------------------------------------------------------------------
  Error Type      example         Does retrying   Handling method
                                  work?           
  --------------- --------------- --------------- ---------------------------------
  **Permanent     JSON format     It didn\'t      **direct** If \`nack\` is
  error**         error, missing  work; trying    `` set and `requeue=false`  ``,
                  fields,         10,000 times    the letter is added to the
                  incorrect field yielded the     dead-letter queue.
                  type            same result.    

  **Temporary     Network jitter  It works, it    Try again locally.
  error**         when publishing might succeed   
                  to backplane    soon.           
  ---------------------------------------------------------------------------------

**Unresolved messages are permanent errors.** This message is inherently
corrupt; no matter how many times it\'s sent, it will never be parsed
successfully. If you use\... `` If `requeue=true`  ``, it becomes a
\"poisonous message\" as we discussed before, looping infinitely in the
queue and blocking all subsequent messages in this sort . Therefore, it
should be **immediately** moved to the dead-letter queue before
continuing to process subsequent messages.

    SwipeEvent event ; 
    try { 
    event = gson . fromJson ( new String ( body , StandardCharsets . UTF_8 ), SwipeEvent . class );
        validate ( event );   // Check if the required fields exist and if the values are within the valid range 
    . catch ( Exception e ) { 
    log . warn ( "Unparseable message, dead-lettering: " + e . getMessage ()); 
    channel . basicNack ( deliveryTag , false , false );   // Directly enter the dead letter queue
        return ; 
    } 
    // Parsing successful, continue processing: deduplication, aggregation...

Also note that Gson If a missing field is encountered during parsing,
**no error will be reported** ; the corresponding field will simply be
set to null or\... 0. Therefore, after parsing, you still need to check
the key fields yourself, such as eventId, which cannot be 0.
null,vertical Must be greater than 0, otherwise a message with an
incomplete format will be treated as a normal message and included in
the aggregation.

## 4. A question worth considering: If publishing a leaderboard fails, should the swipe be resubmitted?

Suppose a swipe has successfully completed aggregation, but fails to
publish to the backplane . Should we then nack it to resubmit?

**That shouldn\'t be the case.** The reason is:

- The aggregation is complete; the vertical of this swipe has been
  added.
- The leaderboard is **derived from the aggregated results** . When the
  next swipe triggers a leaderboard update, the latest leaderboard,
  including that swipe, will be published .
- If this swipe is resubmitted, the deduplication mechanism will
  recognize that it has already been processed and will do nothing. In
  this case, resubmitting is completely pointless.

Therefore, the correct approach is to retry the deployment locally a few
times; if it still fails, log the changes and **ack the swipe as usual**
. The leaderboard will automatically recover to correct status in the
next update.

This kind of situation, where \"you need to reason to reach a
conclusion,\" provides excellent material for reflections on the use of
AI .

## 5. A pitfall to be aware of: Exceptions must not be thrown in callbacks.

If your callback function `handleDelivery` An uncaught exception was
thrown, and the RabbitMQ Java client\'s default exception handler will
**close the channel** . The consequence is:

- All messages on this channel that have not yet been acknowledged will
  be put back into the queue by RabbitMQ .
- The consumers registered on this channel also disappeared, and **no
  one consumed anything from the resort \'s queue thereafter** .
- The queue will keep piling up, and you may not notice it for a long
  time.

Therefore, the outermost layer of the callback function must be wrapped
in a try-catch block **to prevent any exception from escaping** . Inside
the catch block, the system determines whether to nack the error and add
it to the dead-letter queue, or to ack it normally , depending on the
error type .

## 6. A reminder about re-queueing and order.

If you use in certain situations `` Setting `requeue=true`  ``will
**disrupt the order within the sort** . When \`prefetch\` is greater
than 1 , the consumer\... We already have several messages following
this one in our queue. After this message is put back into the queue,
the following messages will be processed first, and it will be
redelivered later.

This is why it\'s recommended **to retry locally instead of relying on a
requeue** : when retrying locally, the message remains in the current
thread, and subsequent messages will wait until it\'s finished
processing before being processed, so the order won\'t be disrupted.

## User

Regarding the specific analysis of swipes and updating the leaderboard,
I feel there are some details that are unclear. My current idea is to
maintain a map with the key being ski resort: day: skierid. This way, we
can save and update everyone\'s information. Then, we design a
leaderboard data structure, still using a map for easy mapping. Because
each ski resort maintains a new form daily, we also need to maintain a
mapping between \"Ski Resort: Day\" and the ranking form . The ranking
form internally uses the skierid-\> Vertically, after each update of a
skier\'s information, it checks the corresponding leaderboard table. If
the skier is in the form, it updates the leaderboard; otherwise, it
compares the skier\'s information with the last-ranked skier to
determine if they are on the leaderboard. If the form is updated, it
pushes the new form back to the consumer? Or does it push it directly to
the broker?

## Claude

Your design is correct overall, and it matches the structure given in
the assignment ( `skierVertical).` and `(ResortLeaderboards `)
corresponds perfectly. Let\'s confirm the details one by one before
answering your last question.

## 1. Two-level Map design: No problem.

    skierVertical: "7:1:40213" → 1350 ( cumulative value of 
    all skiers ) resortLeaderboards: "7:1" → TopN objects     (Each resort has a leaderboard every day)

Processing steps for each swipe :

    ① total = skierVertical.merge("7:1:40213", 200, Long::sum) // Accumulate and get the new total 
    ② board = resortLeaderboards.computeIfAbsent("7:1", k -> new TopN(10)) 
    ③ changed = board.update(40213, total) // Return whether the leaderboard has changed 
    ④ If changed → Publish the new leaderboard

## 2. For internal use in the leaderboard Map: It can be used, but you need to be clear about its cost.

Your idea is to use it internally within TopN. `skierID → vertical` of
Map. Let\'s see what each of the three operations requires:

  --------------------------------------------------------------
  operate              Map                  Lists arranged in
                                            order
  -------------------- -------------------- --------------------
  Determine if a       O(1), directly       O(N), requires
  certain skier is on  `containsKey`        traversal
  the leaderboard                           

  Find the last one    O(N) time            O(1) means the last
                       complexity,          element
                       **requires           
                       iteration** because  
                       the Map is           
                       unordered.           

  Generate a ranking   Sort every time      It\'s already
  list for                                  sorted, just use it.
  publication.                              
  --------------------------------------------------------------

Map and List have one operation that needs to be performed. O(N). But N
only has 10. Iterating through 10 elements only takes tens of
nanoseconds, so **there is no difference in performance between the two
options, and either one can be chosen** .

If you prefer the intuitiveness of a map , then continue using it. A Map
can be used to iterate through the map to find the last element when
needed and to sort it upon release. For a more concise code, a smaller
map sorted in descending vertical order can be used. ArrayList is
reordered after each update. The key is to clearly understand the costs
associated with each choice you make; this is something worth mentioning
in the design documentation.

## 3. Determine several boundary cases when \"there is an update\".

    update(skierID, total):
      On the list → Update his value → Return true (his verticality has changed, so his ranking may also have changed).
      Not on the list yet, but the list isn't full → add directly → return true
      If not on the list, total > last place → replace the last place → return true
      Other cases → return false

Several rules need to be agreed upon in advance:

- **Tie** : If the total score is exactly **equal to** the last-place
  score, should the player be included in the rankings? It is
  recommended to stipulate that only those whose **scores are strictly
  greater than the last-place score should be included in the rankings**
  , so that those already on the rankings will not be pushed out by
  those with the same score.
- **Ranking order** : When two people have the same vertical value, who
  ranks first? It\'s recommended to sort by skierID from smallest to
  largest as a secondary rule. This way, identical data will always
  produce the same ranking list, making it easier to verify the results
  during testing.
- **\"Changes in the rankings\' values\" also counts as an update** :
  because the rankings display the values of each individual. When the
  value of \`vertical\` changes, the content published will naturally be
  different as well.

## 4. Who to push to: Publish directly to the broker.

\"Return to\" The term \"consumer\" needs clarification: **the
aggregation logic itself runs within the consumer program** ; they are
the same process, and there is no concept of \"returning\" to the logic.
Internally, it can be divided into several components:

    Consumer process 
    ├─ Message callback: Receive swipe → Parse → Deduplicat 
    ├─ Aggregator: Update skierVertical and TopN 
    └─ Broadcaster: Publish the new leaderboard to leaderboard.fanout ← Publish directly to the broker

The broadcaster publishes directly to RabbitMQ through a channel .
`The leaderboard.fanout is `then distributed to each server-v2 by
RabbitMQ .

**Which channel should you use to publish?** Channels cannot be shared
between multiple threads, but you have multiple consumer threads (each
responsible for a part). (resort). The simplest approach is: **each
consumer thread has two dedicated channels** , one for consuming data.
Swipe, a tool for publishing leaderboards. Each thread uses only its
own. Channels will not cause conflicts.

Additionally, publishing leaderboards **does not require enabling
publisher confirms** . The reason is the same as discussed in the
previous round: leaderboards are derived states, so even if one is lost,
the next update will include the latest complete leaderboard. This
differs from swipe , which\... Once it\'s lost, it\'s truly lost.

## 5. Push frequency: needs to be controlled.

This was mentioned earlier, and now let\'s take a closer look. Your A1
client generates a skierID that is a random number between 1 and 100,000
, and a resortID\... The number is a random number between 1 and 10 , so
there are approximately 1 million possible combinations of (resort,
skier) . However, there are only 500,000 possible combinations in total.
On average , **each combination requires less than one swipe** . This
means:

- At the start of the test, almost every swipe would get a certain skier
  onto the leaderboard, and the leaderboard would change **very
  frequently** .
- Later on, most people on the list had two or three entries. Swipe, and
  the changes will gradually decrease.

**thousands of leaderboard posts** could be generated per second in the
early stages of testing , and these messages would need to be multiplied
by the number of servers.

The recommended approach is **to merge push notifications** :

    Aggregator thread: When the leaderboard changes, it is not published immediately.
                   Instead, it generates a sorted snapshot, saves it, and marks the resort as "updated". 

    The Broadcaster timer checks every 
    200ms .               Only publish the latest snapshot of the resort that is marked "updated" , then clear the mark.

In this way, each resort will only push a maximum of 5 times per second,
and the pushed-out list will always be the latest one, with any changes
in between being naturally absorbed.

Here\'s a thread safety detail: snapshots are written by the Aggregator
thread and read by the timer thread, so you need to use\...
`AtomicReference` To save, or use `volatile` Modifications. Furthermore,
the snapshot must be **an independent copy** (e.g., creating a new
copy). Instead of directly handing over the internal structure of TopN
(List), it would be better to do so, otherwise the structure might be
being modified when the timer reads it.

## 6. Timestamps in leaderboard messages

A1​ `serverTimestamp` This is the time the server sent the message. Since
the leaderboard is now generated by the consumer , this timestamp should
be\... This is added **when the consumer publishes** . This way, the
push latency measured by the client becomes the complete time from
consumer publish to client receipt, including the hops from consumer →
RabbitMQ → server-v2 → client. This effectively demonstrates the actual
cost of the extra hop in the backplane solution and is excellent data
for analysis in design documents.

## User

Consumer deduplication, accumulation, top 20. Manual ACKs have been
tested. Multi-threading and Broadcaster have been written but not yet
verified. The state is all in memory and is lost upon restart. The next
step is to have LeaderboardEndpoint receive and push the leaderboard
list from fanout . I\'m thinking of opening a separate channel to
receive it, but I haven\'t figured out if concurrent sending in the same
session will cause errors. I\'m also debating whether to push a cached
leaderboard list to new subscribers first.

## Claude

The progress is good. The fact that \"the state is all in memory and is
lost upon restart\" is acceptable in A2 ; persistence is A3 \'s
responsibility. However, it needs to be explicitly stated in the design
document: consumer\... Once restarted, already acknowledged messages can
no longer be retrieved, so the cumulative values and leaderboards will
start from zero and cannot be rebuilt. This in itself explains why the
next assignment requires a database.

I will now answer your three questions in turn.

## 1. Use a separate channel to receive. fanout: Correct direction

Several key points:

    Channel ch = connection . createChannel ();             // Specifically used to receive leaderboards, not placed in 
    ChannelPool ch.exchangeDeclare ( " leaderboard.fanout " , BuiltinExchangeType.FANOUT , true ); 
    String q = ch . queueDeclare (). getQueue ();             // No parameters passed: Named by the server, exclusive, auto- 
    delete ch.queueBind ( q , "leaderboard.fanout " " " ) ; 
    ch.basicConsume ( q , true , deliverCallback , tag -> {});   // autoAck=true

- `queueDeclare()` **Without passing any parameters** , what we get is
  exactly what we mentioned earlier: a \"temporary queue dedicated to
  each server\": the name is automatically generated by RabbitMQ , it
  belongs only to the current connection, and it is automatically
  deleted when the connection is closed. 4 Each server-v2 instance will
  receive an independent queue, so each leaderboard message will be
  replicated to each server.
- **use \`autoAck=true\` .** The leaderboard is derived from the current
  state; if one leaderboard is lost, the next one will still contain the
  complete and latest leaderboard, eliminating the need for manual ACKs
  to ensure reliability.
- **A little trick** : Fanout exchanges ignore the routing key during
  routing , but **it\'s still included in the message** . If the
  consumer sets the routing key to \[redacted\] when publishing the
  leaderboard\... resortID, server You can directly access it within the
  callback. `envelope.getRoutingKey()` Knowing which resort \'s list
  this is, you don\'t need to parse the entire JSON first.
- **exist** `ServletContextListener` **This consumer is started** and
  initialized together with the ChannelPool used for publishing. The
  callback needs to access the subscribers of the LeaderboardEndpoint.
  Therefore, a static method can be provided for LeaderboardEndpoint ,
  such as Map. `` The `broadcast(resortID, json)  ``\` function is used
  for callback calls.

## 2. Concurrent sending within the same session : This will cause an error, and it\'s easier to trigger than you might think.

Tomcat​ `getAsyncRemote().sendText()` There\'s a rule: **if you try to
send the next asynchronous message before the previous one has finished,
an exception will be thrown.** `IllegalStateException `will appear in
the error message. `TEXT_FULL_WRITING . We `write it in A1. `pushAll`
This issue was mentioned before.

Many people believe that \"if only one thread is sending data, there
won\'t be any problems,\" but in reality:

- **Even if there\'s only one thread, it will still trigger.** Fanout
  callbacks are indeed executed one after another, but\... `sendText`
  It\'s **asynchronous** ; it returns immediately after the call, while
  the message is still being sent in the background. The next callback
  arrives shortly after, and it\'s called again. If the previous
  `message `hasn\'t been finished (for example, if the client is reading
  it slowly), an error will occur.
- **Once cached push is added, it will become a true multi-threaded
  application.** `onOpen` The broadcast runs on a Tomcat thread, while
  the broadcast runs on a RabbitMQ callback thread. These two threads
  may send messages to the same session simultaneously.

### Not recommended approach: Locking + synchronous sending

    synchronized ( session ) { session . getBasicRemote () . sendText ( json ); }

This will indeed prevent further errors, but `getBasicRemote()` It is
**blocking** . RabbitMQ will block as soon as one client reads slowly.
The callback thread will be blocked, and **all subscribers of all
resorts will not receive updates** .

### Recommended practice: Configure each session with a sender that \"only retains the latest messages\".

Leveraging a characteristic of leaderboards: **only the most recent
version is meaningful** , while intermediate versions can be discarded.
Therefore, each session only needs one sending status and one \"pending
sending\" slot.

    class SessionSender {
        private final Session session ;
        private final AtomicReference <String> pending =​​ new AtomicReference <>();
        private final AtomicBoolean sending = new AtomicBoolean ( false );

        SessionSender ( Session session ) { this.session​​ = session ; }

        void send ( String json ) { 
    pending.set ( json ) ;​          // Overwrite the old version that hasn't been sent yet.
            drain ();
        }

        private void drain () {
            if (! sending . compareAndSet ( false , true )) return ;   // One message has already been sent; we'll check again once it's finished.
            String msg = pending . getAndSet ( null );
            if ( msg == null || ! session.isOpen ( ) ) { 
    sending.set ( false ) 
    ;​            if ( msg == null && pending.get ( )​ != null ) drain ();   // To prevent missing the messages that were just sent in
                return ;
            }
            try { 
    session . getAsyncRemote () . sendText ( msg , result -> { 
    sending.set ( false ) 
    ;​                drain ();            // After this message is sent, check if there are any new messages waiting to be sent.
                });
            } catch ( Exception e ) { 
    sending.set ( false ) 
    ;​        }
        } 
    }

Its effect is:

- **At any given time, each session can send at most one message** , and
  will never trigger \[ a specific event/condition\].
  `TEXT_FULL_WRITING `.
- **The sending thread is never blocked** ; slow clients only affect
  themselves.
- **Slower clients will automatically skip intermediate versions** and
  only receive the latest rankings, preventing them from accumulating.

`drain()` That line `pending.get() != null` A second check is necessary.
Without it, a situation might arise where another thread happens to be
\"retrieving\" something. null and set the ending to null A new message
was placed between \"false\" and \"false\", but the thread that tried to
acquire the right to send it failed. Since the current thread did not
check again, the message will remain in the pending state and no one
will send it.

### How to connect?

Bundle `Map<String, Set<Session>>` In `Set<Session>` Change to
`Map<Session, SessionSender> `; or in `onOpen` Create a SessionSender,
save to `session.getUserProperties()` Within this session, **all
messages sent to this session must pass through its SessionSender** ,
including cached pushes (discussed below); no one can bypass it and call
it directly. `sendText `.

## 3. When connecting new subscribers, should a cached ranking list be pushed first? It is recommended to push a cached ranking list first.

**reason:**

- **In the later stages of testing, the rankings will change less and
  less** (this was analyzed in the previous round). If the cache isn\'t
  pushed, a subscriber who joins later might have to wait a long time to
  receive their first ranking.
- **Your A1 client has a reconnection function.** Being able to
  immediately see the current state after reconnecting, instead of
  seeing nothing, is what truly constitutes a \"graceful reconnection.\"
- **The cost is minimal.** Only one needs to be maintained on each
  server .
  `` The `ConcurrentHashMap<resortID,  ```latest ``` JSON>`  ``is
  updated every time a list is received in the fanout callback.

**Implementation order:**

    broadcast thread (fanout) Callback): First update the cache → then call sender.send() 
    for each subscriber individually. onOpen:                First, register the session in the subscriber Map → then read the cache and send it via sender.send() .

By doing one thing first and then the other on both sides, new
subscribers will not miss any updates that come during the registration
process.

### However, there is also a competitive issue here.

Even following the above order, such overlaps may still occur:

    onOpen:  Register session 
    onOpen:  Read the cache → Get v1 
    broadcast:    Cache updated to v2 → sender.send(v2) 
    onOpen: sender.send(v1) ← The old version overwrites the new version in the pending list.

The result is that new subscribers end up receiving the old version. v1,
and it won\'t be fixed until the next update.

**The solution is to add version numbers to the rankings.** Each resort
is managed by only one consumer thread, so this thread can maintain an
incrementing sequence number for each resort and store it in the
leaderboard message. SessionSender It records the highest version number
it has received, **only accepting versions newer than it** , and
discarding older versions. This way, subscribers will not see the list
\"backwards,\" regardless of the order in which messages arrive.

This version number also has an additional use: the client can check
whether the received sequence numbers are consecutive, thereby
estimating how many intermediate versions were skipped in the merge
push.

## Regarding the multithreading part that you haven\'t verified yet.

For the Broadcaster and multithreaded parts, there is a relatively
simple verification method: **use the same input, run it once in
single-threaded mode and once in multithreaded mode, and compare whether
the final top-N results are completely consistent.** As long as each
resort is processed strictly by a single thread, the results of the two
tests should be exactly the same. If discrepancies occur, it indicates
that there is a contention or ordering issue between threads somewhere.

## User

I have a question about the version number. The serial number is
incremented automatically by the consumer thread, so if the consumer
restarts, it starts from 0 again. What about the server? The
SessionSender still remembers the previous largest version, so wouldn\'t
all the new lists be discarded as old ones? Can\'t we just use
serverTimestamp for comparison? Anyway, it\'s set when the consumer
publishes, and it won\'t revert after a restart.

## Claude

You\'re right, that was a real flaw in my previous solution. (consumer)
After a restart, the sequence numbers will start from 0 again, but the
SessionSender will still remember the maximum value before the restart.
Therefore, all new leaderboards will be discarded as \"old versions\".
Subscribers will remain on the leaderboard before the restart until the
new sequence number catches up with the old maximum value, which may
take a long time, or even never catch up.

By the way, this happens to be the topic of \" AI \" in the reflections
on AI usage. A typical example of \"the given solution has problems,
which you discovered yourself\" is worth recording truthfully.

## Using serverTimestamp for comparison: feasible, but with three risks.

The advantages of your approach are clear: the timestamp doesn\'t depend
on the consumer \'s memory state, won\'t roll back after a restart, and
doesn\'t require any extra fields. However, it relies on **a wall
clock** , so there are a few risks to be aware of:

**1. The clock on the wall occasionally goes back.** The previous
article `Instant.now()` As mentioned before, NTP Time synchronization
may adjust the system time. On Amazon Linux , chrony normally fine-tunes
gradually, but during the first few synchronizations after boot, if the
deviation is significant, it may **jump abruptly** . If the clock jumps
backward, the leaderboards for the following period will be discarded.
This is rare during testing, but not impossible.

**2. Situations where the time is the same.** At millisecond precision,
two leaderboards from the same resort might have the same timestamp. In
your design, the merge push is done every 200ms , so it\'s unlikely that
two leaderboards from the same resort would be generated within the same
millisecond. However, you need to be clear about whether to use
\"greater than\" or \"greater than or equal to\" when making
comparisons.

**3. Clock deviation between different machines.** If the consumer
restarts and runs on **a different machine whose clock is** 2 seconds
slower than the original machine , then all leaderboards published
within the first 2 seconds will be discarded. With NTP synchronization,
the deviation is usually only a few milliseconds, which has a very small
impact, but this dependency does exist.

For the scale of this project, the actual impact of these three risks is
minimal. Therefore, **using timestamps is acceptable , and** it\'s
sufficient to specify in the design document that it relies on NTP
synchronization.

## A more reliable approach: Start the era + sequence number

If you want to avoid both reboot and clock issues at the same time,
there is a common pattern: **the version number consists of two parts**
.

    Version number = (epoch, seq)
      epoch: consumer The startup timestamp remains constant throughout the entire runtime.
      seq:  Within this epoch , each resort starts from 0 and increments 

    in comparison rules: first compare... epoch, epoch Major update; epoch If they are the same, then compare seq.

- **After the consumer restarts** , it will get a new and larger one.
  The epoch means the new ranking will definitely be accepted. This
  solves the problem you pointed out.
- **During the same run** , the comparison is\... seq is completely
  independent of the clock, will not cause errors due to NTP
  adjustments, and will not produce duplicate version numbers.
- It still relies on a clock, but **only at the moment of restart** : it
  only requires that \"the clock after restart is later than the last
  time it started\", which is much more lenient than \"the clock must
  monotonically increase with each release\".

This approach is common in distributed systems and is often referred to
as generation, epoch , or fencing token.

## Taking a step back: version numbers are actually not necessary.

Looking back, we introduced version numbers to solve\... `onOpen` **The
race condition between push cache and broadcast** . This race condition
can actually be eliminated structurally: make **the \"broadcast\" and
\"new subscriber registration\" of the same resort execute mutually
exclusively.**

    private static final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    static void broadcast(String resortID, String json) {
        synchronized (locks.computeIfAbsent(resortID, k -> new Object())) {
            cache.put(resortID, json);
            for (SessionSender s : sendersOf(resortID)) s.send(json);
        }
    }

    @OnOpen
    public void onOpen(Session session, @PathParam("resortID") String resortID) {
        SessionSender sender = new SessionSender(session);
        synchronized (locks.computeIfAbsent(resortID, k -> new Object())) {
            register(resortID, session, sender);
            String cached = cache.get(resortID);
            if (cached != null) sender.send(cached);
        }
    }

Thus, for the same In Resorts, the processes of \"updating the cache and
broadcasting\" and \"registering and sending the cache\" cannot be
interleaved. New subscribers either register before the broadcast,
receive the cache, and then receive the broadcast; or they register
after the broadcast, reading the latest cache. In either case, there
will be no issue of the old version overwriting the new version.

In my previous discussion about channel pools , I mentioned that the
drawback of locking is \"waiting on a busy resource.\" Why doesn\'t
locking work here? Because the code executing within the lock\...
`sender.send()` It is **non-blocking** : it simply sets\... The message
is pending, and an asynchronous send is initiated, then it returns
immediately. Therefore, the lock is held for a very short time, only a
few microseconds even with many subscribers. Furthermore, broadcasting
only executes on a single RabbitMQ callback thread, so the only thing
contending for the lock is the occasional event\... `onOpen `.

The remaining question is: will **the order in which messages arrive**
at the server be disrupted? In your design, lists from the same resort
are published by only one consumer thread, pass through the same fanout
exchange, enter the same queue, and are then consumed by the same
channel . (RabbitMQ) This ensures that messages are delivered in order
along a single path. Therefore, version numbers are not needed as long
as there is no situation where \"the same resort is handled by two
consumers simultaneously \".

## suggestion

**Using a scheme where each resort has its own lock, but without the
version number.** It is the simplest, does not rely on a clock, and does
not have the problem of restarting.

If, in the future, multiple consumers need to manage the same resort
simultaneously (e.g., for failover or further segmentation as discussed
earlier), messages may arrive via different paths, compromising message
order. In that case, (epoch, seq) version numbers will be introduced.
The design document can briefly mention that the current design relies
on the premise that \"each resort has only one publisher.\" If this is
extended to multiple publishers, version numbers will be necessary.

## User

The assignment requires the ranking list to be determined by the number
of card swipes I issue. Whatever card swipes are issued, the ranking
list will change accordingly. How can I design a system to prove this?
Keep it as lightweight as possible.

## Claude

This can be done in two layers: **a manual smoke test that can be
completed in a few minutes** , used to take screenshots as evidence;
**and an automated reconciliation that is executed after the load test**
to prove that the results are still completely correct even with a scale
of 500,000 records. Neither of these requires the introduction of new
components.

## First layer: Manual smoke test (lightest, suitable for screenshots)

The idea is: **send a few messages that you can mentally calculate the
result of. Swipe, then check if the rankings your subscribers receive
match your calculations.**

1.  Subscribe to resort 7 using wscat :
    `wscat -c ws://<ALB>/<app>/leaderboard/7`
2.  Select a load test **that will not be used** . dayID (e.g.) (99), so
    that this list is brand new and will not be affected by other data.
3.  Send several messages using curl swipe, for example:

<!-- -->

    Skier 111: Lift 1 , 3 runs → 3 × v1 
    Skier 222: Lift 40 , 1 run → 1 × v40 
    Skier 333: Lift 1 , 1 run → 1 × v1

Based on the values in lift_vertical.json , the expected ranking and
each person\'s vertical value are calculated in advance.

4.  After each message is sent, observe how the rankings received by
    wscat change: skier 111 \'s vertical value increases step by step,
    and the ranking is adjusted accordingly. The final result is exactly
    the same as what you calculated.

showing the curl command and wscat output together provides the most
direct evidence that \"the leaderboard is determined by the swipe sent
.\" The entire process takes only a few minutes and requires no new
code.

## Second layer: Automatic reconciliation after load testing (proving it works correctly even at large scale).

Your A1 client knows which **swipes it has sent** and that subscribers
are receiving the leaderboard. Therefore, you only need to let the
client calculate the expected result locally and then compare it with
the final leaderboard received.

**Additional things the client needs to do:**

    Sending end: After receiving a 201 swipe for each item , locally increment 
    expected[resortID][skierID] += vertical[liftID]. 
    Subscribing end: Record the last leaderboard received by each 
    resort . After load testing: Wait for the leaderboard to stabilize → use expected to calculate the top-N for each resort → compare each item with the last received leaderboard.
    // Sender: After 201, call (one Map per resort, or use 
    ConcurrentHashMap ) expected.get ( resortID ) .merge ( skierID , ( long ) verticals [ liftID ], Long :: sum ); 

    // After the load test is complete, for each resort 
    : List <Entry> want = topN ( expected . get ( r ), N );   // Use the exact same sorting rules as the 
    consumer List <Entry> got = lastBoard.get ( r ) ; 
    System.out.println ( " resort " ) ; + r + ": " + ( want . equals ( got )) ? "MATCH" : "MISMATCH" ));

10 lines were printed. `The MATCH test `proved that after 500,000 swipes
passed through the entire chain of ALB, server, RabbitMQ, consumer, and
backplane , the final list and the data sent were completely consistent.

In terms of memory, there are approximately 400,000 ( resort, skier)
combinations, which would require several tens of memory blocks to store
using a HashMap. MB, there is no problem for the client.

## For reconciliation results to be reliable, several prerequisites must be met.

**1. Use a new one for each test. Check the dayID, or restart the
consumer first.** This is the easiest point to overlook. (consumer) The
state is stored in memory. If you run the program twice consecutively
with the same dayID , the second leaderboard will include the
accumulated value from the first run, and reconciliation will definitely
fail. The simplest solution is to have the client use a new dayID each
time the program runs .

**2. Wait for the rankings to stabilize before comparing. When** the
ingestion ends, there may still be unprocessed messages in the queue,
and the merge push may still have one last message yet to be sent.
Therefore, wait until \"no new rankings are received for several
consecutive seconds\" before taking the last one for comparison, and
then turn off the subscriber.

**3. The sorting rules must be completely consistent with those of the
consumer .** Rules such as sorting by skierID in case of a tie and
determining who makes the list when there\'s a tie for Nth place can all
lead to discrepancies at the boundary if they differ even slightly. The
safest approach is to extract the Top N sorting logic into a smaller
class, consumer. Share the same code with the client.

**4. Only accumulate swipes that receive a 201 error .** A failed swipe
should not be included in the expected value.

**5. The client also needs lift_vertical.json.** Simply copy the same
file to the client\'s resources directory.

## If it appears MISMATCH itself is also very valuable.

Reconciliation failures typically have only a few causes, each
corresponding to a real system problem:

- **The expected value is smaller than the actual value** : a certain
  swipe was counted twice. The most likely reason is the situation we
  discussed earlier: the client retried after a timeout, and the
  server\... New ones were generated The eventId cannot be recognized by
  the deduplication mechanism.
- **The expected value is larger than the actual value** : A swipe was
  lost, for example, the server returned a value that was not found.
  201, but the message was not actually queued, or the consumer did not
  process it.
- **Inconsistency only occurs near the last position** : the sorting
  rules are inconsistent.

Therefore, reconciliation not only proves the system is correct, but
also directly tells you where the problem lies when issues arise. If you
encounter problems caused by duplicate calculations during formal
testing\... A MISMATCH, truthfully written into the report and explained
using the idempotent key schemes discussed earlier, will demonstrate
your understanding of the system better than a perfect MATCH .

## User

How do I deploy it? For example, what are my current broker and server
configurations? The consumer and client are all on the same local
machine. In the future, will it be necessary to distribute the broker,
server, and consumer to different AWS machines? Please explain in detail
how this can be implemented, as I have no experience in this area.

## Claude

Yes, ultimately they need to be deployed separately on different EC2
instances , which is also an explicit requirement of the job: RabbitMQ
The server and consumer each run on separate EC2 instances, server-v2.
Run 1, 2, and 4 instances and place them behind ALB . The reason for
separate deployment is to allow each component **to have its own
dedicated CPU and memory** . If all of them were placed on a single
machine, they would compete for resources, and the measured throughput
and bottlenecks would not reflect the true nature of a distributed
system.

The following steps will be explained step by step, following the
approach of \"understanding the concepts first, then doing things in
order\".

## I. Target Architecture

                        Your computer (client) 
    │ HTTP / WebSocket 
    ▼ 
    ┌──────── ALB ─────────┐ (Public network accessible, port) 80) 
    ▼ ▼ ▼ 
    server-v2 #1 server-v2 #2 …… (Tomcat, port) 8080) 
    │ Publish swipe ▲ Receive leaderboard 
    ▼ │ 
    ┌──────── RabbitMQ ────────┐ (Port) 5672, Management Interface 15672) 
    │Consumer Swipe ▲ Ranking List Release 
    ▼ │
                  consumer (a standalone Java program)

All the machines are placed **The us-west-2 region** uses the same
default VPC.

## Second, understand two core concepts.

### 1. Public IP and Private IP

Each EC2 instance has two IP addresses:

- **Public IP address** : Used when accessing it from the internet, such
  as when you log in via SSH or open the management interface in a
  browser. **The public IP address will change every time you stop and
  start the instance** (unless you have bound it to an Elastic IP).
- **Private IP** : Used within the same VPC for communication between
  machines, in the form of\... `172.31.xx. `**The value will not change
  after \`stop\` and** \` **start\`** .

Therefore, there is an important principle: **when machines connect to
each other, private IP addresses should always be used.** For example,
when server-v2 and consumer connect to RabbitMQ , the configuration
should be RabbitMQ \'s private\... This approach has three advantages:
the configuration remains effective after restarting the instance;
traffic bypasses the public network, resulting in lower latency; and
traffic within the same availability zone is free.

### 2. Security Group Group)

**firewall rules** for each machine , determining \"who can access which
ports on my machine.\" In A1 , you only use the rule \"allow my IP to
access port 8080 and\...\" 22\". With more machines now, the best
practice is **to create a security group for each role type** and **use
the security group as the access source** , instead of entering specific
IP addresses:

  --------------------------------------------------------------
  Security Group       Entry rules          illustrate
  -------------------- -------------------- --------------------
  `sg-alb`             80 ← Your IP (or     Client access to ALB
                       0.0.0.0/0)           

  `sg-server`          8080 ← `sg-alb `; 22 Only ALB can forward
                       ← your IP; 8080 ←    the traffic.
                       Your IP (for         
                       debugging)           

  `sg-rabbit`          5672 ← `sg-server`   Only the server and
                       and `sg-consumer `;  consumer can connect
                       15672 ← Yours IP; 22 to RabbitMQ.
                       ← Your IP            

  `sg-consumer`        22 ← Your IP         Consumers only need
                                            to actively connect
                                            to others; they
                                            don\'t need to be
                                            accessed by others.
  --------------------------------------------------------------

\"8080 ← `sg-alb `\" means: **all belonging to** `sg-alb` **All machines
in this security group** can access port 8080. This way, no matter how
many machines you add later\... The rules do not need to be modified
regarding the servers or their IP addresses .

Keep the outbound rules at the default \"Allow All\".

## III. Before Deployment: Extract the Configuration from the Code

This is the most crucial step in transitioning from \"local operation\"
to \"distributed deployment.\" Now, in your code, RabbitMQ\... The
address is likely to be written as `localhost `. After deployment, each
machine needs to connect to a different address, **which can no longer
be hardcoded in the code** ; instead, it should be read from **the
environment variables** .

    String rabbitHost = System.getenv().getOrDefault("RABBIT_HOST", "localhost");
    String rabbitUser = System.getenv().getOrDefault("RABBIT_USER", "guest");
    String rabbitPass = System.getenv().getOrDefault("RABBIT_PASS", "guest");

The effect of this is that **the same WAR file and the same JAR file can
run directly on both the local machine and AWS** . On the local machine,
no environment variables are set; default values are used. localhost; on
AWS , specify the private IP address for RabbitMQ via environment
variables .

**the hostname** directly for serverId (
`InetAddress.getLocalHost().getHostName() ) instead of specifying it through a configuration file. The reason will be explained in step `4
later .

## IV. Deploy in sequence

It is recommended to strictly follow this order, **verifying each step
before proceeding to the next** .

### Step 1 : Deploy RabbitMQ

**Create instance** : t2.micro, Amazon Linux 2023, security group
selection `sg-rabbit `.

**Installing via Docker** is the simplest way and avoids version issues
that occur when manually installing Erlang .

    sudo dnf install -y docker
    sudo systemctl enable --now docker

    sudo docker run -d --name rabbitmq --restart unless-stopped \
      -p 5672:5672 -p 15672:15672 \
      -e RABBITMQ_DEFAULT_USER=skitrak \
      -e RABBITMQ_DEFAULT_PASS=<你的密码> \
      rabbitmq:4-management

- `--restart unless-stopped `: RabbitMQ will restart after the machine
  restarts. It will start automatically.
- `-management` The image version comes with a built-in management
  interface, so no further execution is required.
  `Enable rabbitmq-plugins `.
- Users created via environment variables **can log in from other
  machines** , unlike the restriction that guest users can only log in
  from the local machine.

Execution is required later. `rabbitmqctl` When giving a command, add
the following before it: `docker exec `:

    sudo docker exec rabbitmq rabbitmqctl list_queues name messages

**Verification** : Open in your browser If you can log in successfully
using the skitrak user `at http://<RabbitMQ ``public ``IP>:15672 `, then
you\'ve succeeded. Note down **the private IP address** of this machine
; you\'ll need it later.

### Step 2 : Deploy the consumer

**Create instance** : t2.micro, Amazon Linux 2023, security group
selection `sg-consumer `.

**When installing Java** , the version must be at least as high as the
version you used to compile it.

    sudo dnf install -y java-17-amazon-corretto-headless

**Package and upload locally** : consumer It is a standalone program and
requires\... `maven-shade-plugin` Package it into a fat jar containing
all dependencies , just like the A1 client.

    mvn clean package 
    scp -i < key > .pem target/consumer.jar ec2-user@ < consumer public IP > :~

**Use systemd to run it in the background and automatically restart it
after a crash.** The \"auto-restart on failure\" requirement in the
assignment is implemented using this. Create a file.
`consumer.service `:

    [Unit] 
    Description = SkiTrak Consumer 
    After = network-online.target 

    [Service] 
    User = ec2-user 
    Environment = RABBIT_HOST=<RabbitMQ's private IP> 
    Environment = RABBIT_USER=skitrak 
    Environment = RABBIT_PASS=<your password> 
    ExecStart = /usr/bin/java -Xmx512m -jar /home/ec2-user/consumer.jar 
    Restart = always 
    RestartSec = 5 

    [Install] 
    WantedBy = multi-user.target
    sudo cp consumer.service /etc/systemd/system/ 
    sudo systemctl daemon-reload 
    sudo systemctl enable --now consumer # Start the service and configure it to start automatically on boot 
    journalctl -u consumer -f                # View logs in real time

- `Restart=always` and `RestartSec=5 `: RestartSec will be reset 5
  seconds after the program exits for any reason. It will restart
  automatically in seconds.
- `-Xmx512m `: Limits the JVM\'s memory usage to a maximum of 512 MB .
  t2.micro There\'s only 1 GB of memory in total , so we need to leave
  space for the operating system.

**Verification** : On the Connections page of the RabbitMQ management
interface , you should see a connection from the consumer\'s private IP
; on the Queues page, the Consumers column for each resort queue should
display 1.

### Step 3 : Deploy the first server-v2

**Create an instance** : t2.micro, select the security group. The
process of installing Java and Tomcat 9
`on sg-server is exactly the same as `on A1 .

**Set environment variables** : Create `<TOMCAT_HOME>/bin/setenv.sh`
Files, Tomcat It will be automatically read at startup:

    export RABBIT_HOST =< RabbitMQ private IP > 
    export RABBIT_USER = skitrak 
    export RABBIT_PASS = < your password >

If your Tomcat is started via systemd , you need to write these
variables in the Tomcat systemd unit file. `Environment=` The form is
the same as that of consumer .

**Two things need to be confirmed:**

- `conf/server.xml` It is set up `` `maxKeepAliveRequests="-1"`  ``is
  something we discussed in A1 .
- **Tomcat should be configured to start automatically on boot via
  systemd** , rather than being manually started through the screen .
  The next step will explain why this is important.

**Deploy the WAR in the same** way as A1 .

**Verification** : Directly access this machine (without going through
any intermediaries) ALB):

    curl -i http://<server 公网 IP>:8080/<app>/health
    curl -i -X POST -H "Content-Type: application/json" -d '{"time":217,"liftID":21}' \
      http://<server 公网 IP>:8080/<app>/skiers/7/seasons/2026/days/99/skiers/123

receiving a 201 response , you should see the message published to the
resort.7 queue and consumed by the consumer in the RabbitMQ management
interface. **At this point, the entire process has successfully
completed on AWS for the first time.** At this point, the A1 client can
be used to directly stress test this machine as a \"single instance\"
test result.

### Step 4 : Copy more server-v2 instances

the first server is configured, there\'s no need to manually configure
the second or third one. **The AMI** (Machine Image) takes a snapshot of
it and then uses the snapshot to launch a new instance.

1.  EC2 console → Select the first server → **Actions → Image and
    templates → Create image** .
2.  Wait for the AMI to be created ( check the AMIs page on the left ;
    the status should change to available ).
3.  **Launch instance from AMI** , quantity to fill in 3. Security group
    also selected `sg-server `.

The newly started machine already has it installed. Tomcat, already
deployed WAR, and the configuration in setenv.sh .

This is the reason behind the two design decisions mentioned above:

- **Tomcat must be configured to start automatically on boot** ;
  otherwise, Tomcat will not start automatically on a new machine. It
  won\'t run.
- **The serverId uses the hostname** because each new machine has a
  different hostname, so all machines replicated by AMI can be exactly
  the same, without needing to modify the configuration on each machine
  individually.

**Verification : Perform the** curl test in step 3 once on each new
machine .

### Step 5 : Create ALB

**5.1 Creating a Target Group Group)**

EC2 Control Panel → Left Side **Target Groups** → Create target group:

- Target type: **Instances**
- Protocol and Port: **HTTP, 8080**
- The path to the health check is: `/<app>/health `. You must include
  the context path; otherwise, the health check will fail with a 404
  error, and all machines will be deemed unhealthy.
- The parameters for the health check should be set according to the job
  requirements: Interval 30 seconds, Timeout 5 seconds, Healthy
  threshold 2, Unhealthy threshold 3.
- Registration goal: To add instances of server-v2 . When testing a
  single instance, only one instance will be added initially, followed
  by gradual increases to two , four, etc. tower.

Once created, enter this target group. **Attributes → Edit** , to enable
**Stickiness** , type selection **Load balancer generated cookie** .

**5.2 Creating an ALB**

Left side **Load Balancers** → Create → **Application Load Balancer** :

- Scheme: **Internet-facing**
- Network mapping: **Select at least two subnets in different
  Availability Zones** . This is a hard requirement of ALB , and must be
  done even if your servers are all in the same Availability Zone.
- Security group: `sg-alb`
- Listener: **HTTP:80** , forwards to the target group created in the
  previous step.

After creation, enter ALB . **Attributes → Edit** , and\... **Connection
idle timeout** Set it to longer than 60 seconds, for example, 120
seconds. ALB Native support WebSocket does not require any additional
switches to be turned on.

**Verification** : On the Targets page of the target group, the status
of all machines should be displayed as follows. **healthy** . Then
access it via ALB \'s DNS name:

    curl -i http:// < ALB 's DNS name > / <app> / health 
    wscat -c ws:// < ALB 's DNS name > / <app> / leaderboard/7

### Step 6 : Client connects to ALB

Change the client\'s server address to:

    http://<ALB 's DNS name>/<app> 
    ws://<ALB 's DNS name>/<app>/leaderboard/{resortID}

Note that the port has changed. 80, so it doesn\'t need to be written in
the URL . `:8080 `.

**Here\'s a common pitfall** : if your client is using Apache HttpClient
5, it **saves cookies by default** . After ALB enables sticky sessions,
it will return a cookie in the first response. `AWSALB` Cookies are
stored on the client side, and all subsequent POST requests will carry
this information. Cookies ensure that **all requests are consistently
sent to the same server .** This way, your \"4\" The \"server test\"
actually only had one server working, resulting in a completely
unbalanced load.

The solution is **to disable cookie management** on the HttpClient used
by Ingest :

    HttpClients . custom () . disableCookieManagement () ...

What sticky conversations really need to do is\... WebSocket. In fact,
WebSocket\... Once a connection is established, it is a fixed TCP
connection and will remain on the same server . Therefore, for Ingest
POST requests , disabling cookie management and allowing ALB to
distribute requests evenly across the servers is the desired outcome.

## V. Costs and Common Pitfalls

**1. Costs.** This time, a total of 4 RabbitMQ instances, consumer
instances, and 4 machines are needed. There are 6 servers in total.
t2.micro, plus one ALB. The free quota is 750 hours per month **across
all instances .** If all machines are running simultaneously, the usage
will be exhausted in approximately 5 days. **ALB ( Allow-Up Load
Balancer) charges by the hour as long as it exists** , even without any
traffic. Recommendation:

- Set up a **budget alert** in AWS Billing , such as sending you an
  email notification if your budget exceeds \$ 5 .
- When not testing **stop** Drop all instances.
- , use one server to debug the functionality, and then conduct
  **centralized testing on** two and four servers.
- After completing all tests and taking screenshots, delete ALB.

**2. CPU Credits for t2.micro. The** t2 series is a \"bursting\"
instance, normally operating at only 10% of its base CPU performance,
only reaching full speed when CPU credits are consumed. Prolonged stress
testing will deplete these credits, causing **a sudden drop** in
performance and throughput. If you observe a sudden drop in throughput
during testing, you can check it in CloudWatch. `CPUCreditBalance`
Indicators. Explaining this phenomenon in a report also provides
excellent analytical material.

**3. RabbitMQ memory alerts. By default,** RabbitMQ will alert users
when memory usage reaches a certain percentage of system memory
(approximately 40% to 100% depending on the version). **A memory alert**
is triggered when the queue is at 60% capacity , and all publish
reception is suspended. On a t2.micro server with only 1 GB of memory ,
this alert is easily triggered if the queue is heavily backed up. After
triggering, the server\... Your message will be blocked when you post
it. `waitForConfirmsOrDie` It will time out and return. 503. On the
Overview page of the management interface, if the memory column turns
red, it means an alert has been triggered.

**4. The public IP address will change. After** stopping and restarting
the instance, SSH\... The public IP address used to access the
management interface will change, so you need to check it again in the
EC2 console. The configuration between machines uses private IP
addresses. Since it uses an IP address, it will not be affected, which
is why we emphasized using a private IP address earlier .

## VI. `/deployment` What should be put in the directory?

Assignment Requirements Submission `/deployment` The table of contents
perfectly organizes all the above content:

    deployment/ 
    ├── rabbitmq/setup.sh # docker run command and policy settings 
    ├── consumer/consumer.service # systemd unit file 
    ├── server/setenv.sh # Tomcat environment variables (password is replaced with placeholders) 
    ├── alb/README.md # target group, ALB Configuration parameters and screenshots 
    └── security-groups.md # Rules for the four security groups

Note that **you should not commit your actual password to Git** ; use a
placeholder instead.

If you would like to save these deployment steps as a file so you can
easily refer to them as you go, I can help you compile them into a
document.

## User

the requirement of \"Consumer lag \< 100 ms\" mean? How is the boundary
defined? Does it refer to the time from when a swipe is sent by the
server until its information is recorded in the consumer\'s table? Or
does it refer to after a successful merge and leaderboard refresh? How
is this defined?

## Claude

The assignment doesn\'t provide a strict definition of this metric, so
**you\'ll need to provide your own clear definition and explain your
reasoning in the report** . Let\'s look at a few possible boundaries
first, and then I\'ll give my recommended definition.

## Three possible boundaries

    The server receives the request and publishes the message — ▶ The message is queued in the RabbitMQ queue — ▶ The consumer receives the message — ▶ Aggregation complete — ▶ The leaderboard is pushed out. 
    (T0, T1, T2, T3 

    solutions) A: How long did the T0 → T1 message wait in the queue ?
     B: How long did it take from release to status update completion for 
    T0 → T2 ? C: How long did it take from the release of T0 to the ranking being pushed out?

**Option C is not suitable as a consumer lag.** It includes the wait
time for merged pushes. We designed it to push every 200ms , and this
alone could cause a swipe to wait for close to \[time value missing\].
200ms. Therefore, this specification cannot be lower than **that by
design.** 100ms. Moreover, it no longer measures \"consumer\". It\'s not
about \"keeping up,\" but about the \"freshness\" of the entire chain.
It\'s a valuable metric, but it should be named separately, for example,
swipe-to-leaderboard latency, and reported separately.

**Option A and Option B is very small.** The aggregation takes only a
few microseconds, so T1 and T2 are almost simultaneous.

## Recommended definition: Option B

> **Consumer lag = publishes a message from the server The swipe
> continues until the consumer completes the aggregation of this swipe
> (status updated, ready). The time elapsed for (ack).**

The reason for choosing this definition is:

1.  **It measures precisely the \"consumer\" \"Can it keep up with the
    production speed?\"** If the consumer \'s processing speed cannot
    keep up, messages will accumulate in the queue, and this process
    will take longer and longer. This is entirely consistent with the
    intention of the job.
2.  **The endpoint should be set to \"Aggregation complete\" instead of
    \"Message received\".** This is how we can truly see when this swipe
    starts to affect the system state: from this moment on, querying
    this swipe \... The vertical line already contains this swipe.
3.  **It does not include push notification design options.** The
    interval for merging pushes is a separate parameter and should not
    be mixed with the consumer \'s processing capacity.

## Its relationship with \"queue depth \< 1000\"

These two goals are actually **two sides of the same coin , and can** be
connected using Little\'s Law :

    Average number of messages in the queue = message arrival rate × average waiting time per message

Assuming the swipe arrival rate is 5000 swipes per second, lag yes
100ms:

    5000 messages/second × 0.1 seconds = 500 messages

In other words, lag When the latency is below 100ms , there are
approximately 500 messages in the queue , which is exactly within the
target of 1000. You can verify this relationship in your report using
actual test data: for example, what is the average lag in actual tests ,
what is the average queue depth in actual tests, and whether both
satisfy Little\'s Law. This would be a very convincing analysis.

## How to measure

The message itself contains `timestamp` field (server) (This was added
during the publishing process), so it only needs to be calculated after
the consumer aggregation is complete:

    // After aggregation is complete and before ack, 
    long lagMs = System.currentTimeMillis ( )​ - Instant . parse ( event . timestamp ) . toEpochMilli (); 
    lagRecorder . record ( lagMs );

A few points to note:

**1. The server needs to add a timestamp just before it is published.**
If the timestamp is added when the request is received, lag This will
include the time the server itself spends processing the request, such
as validation and waiting time to borrow channels . The closer the
timestamp is to the current position\... The more `basicPublish `is
measured, the higher the latency on the consumer side will be.

**2. Do not use the built-in timestamp attribute in AMQP .** RabbitMQ
also has a message attribute. `timestamp` The field has **a precision of
only seconds** , which is completely insufficient for measuring latency
at the 100ms level. Therefore, the millisecond precision field in the
message body should be used.

**3. There is clock skew across machines.** The timestamp is generated
by the server \'s clock, while the lag calculation uses the consumer \'s
clock, which is exactly the same as the push latency issue in A1 . Both
EC2 instances synchronize their clocks via Amazon Time Sync Service ,
and the deviation is usually within 1 millisecond, which is negligible
relative to the 100ms target. However, this should be stated in the
report.

**4. The waiting time in the prefetch buffer is also taken into account.
When** the prefetch setting is large, messages will first be sent to the
consumer \'s local buffer for queuing before being processed. This
waiting time is also included. The lag occurs because the swipe hasn\'t
actually been processed yet. This is useful for tuning: if the prefetch
setting is too high, lag will occur. It will also increase accordingly.

**5. It records the distribution, not individual numbers.** 500,000 long
strings only occupy about 4 MB of memory, which can all be stored and
then calculated later . mean, p95, p99, max. It is also recommended to
print the values for this time period **every 10 seconds .** This allows
you to plot the lag curve over time. If the consumer can\'t keep up,
you\'ll see the lag continuously increase, which corresponds to the
\"zigzag\" queue curve described in the assignment.

## How to write it in a report

Clearly state your definition in one or two sentences, and then explain
why you define it this way:

> We define consumer lag as the time from when the ingest server
> publishes a swipe (the message `timestamp `) to when the consumer has
> applied it to the aggregate state. This excludes the leaderboard
> broadcast interval, which is a separate design parameter reported as
> end-to-end latency. Measurements span two hosts synchronized via
> Amazon Time Sync Service (sub-millisecond skew).

Then the distribution of lag (mean, p95, p99) is given. The curve
changes over time, and its Little\'s Law validation with queue depth is
shown, with a separate report on the end-to-end latency from swipe to
leaderboard push.

## User

The arrival of swipes involves a large number of transactions. Are you
going to calculate and count every single one of them? How would you
implement that?

## Claude

Yes, every single one is counted, but **the method of counting must be
chosen correctly** . The key is: the calculation itself is cheap; what
can really slow down the system is **the way it is stored and
aggregated** .

## Let\'s calculate the cost of each message first.

  -------------------------------------------------------------
  operate                        Approximate time taken
  ------------------------------ ------------------------------
  `System.currentTimeMillis()`   tens of nanoseconds

  `Instant.parse(...)` parse     Approximately 1 microsecond
  timestamp                      

  Increment one on a counter     Several to tens of nanoseconds
  -------------------------------------------------------------

It takes about 1 microsecond to process all of them. Even if 10,000
messages are processed per second, it only takes up about 1% of the CPU
time. So **calculating every single message is perfectly feasible** ,
and sampling is not necessary.

These are the practices that should really be avoided:

- **Each message is either logged as a line or written to a file once**
  : I/O It\'s several orders of magnitude slower than computation.
- **Each value is placed into a shared, locked List** : all consumer
  threads will compete for the same lock.
- **use** `ConcurrentLinkedQueue<Long>` **For this type of container** :
  each long must be boxed into a Long object, 50 Tens of thousands of
  objects can put considerable pressure on the GC .

## Recommended approach: Use a histogram with fixed bins.

The idea is **to not store every specific value, but only count \"how
many messages fall within each millisecond interval\".**

    bucket 0: lag Message count 
    bucket with 0ms 1: lag The number of messages 
    per 1ms ... 
    bucket 999: lag A message 
    bucket with a duration of 999ms 1000: Number of messages (overflow bucket) with a lag ≥ 1000ms

- **Record a value** : find its corresponding bucket and increment the
  count by one. The time complexity is O(1).
- **The memory usage is fixed** : regardless of the number of messages
  processed, it only occupies 1001 counters and will not increase with
  the number of messages.
- **An accuracy of 1ms is more than sufficient** for a target of 100ms .
- **Calculate percentiles** : Start counting from bucket 0 and increment
  sequentially. When the incremented value first reaches a certain
  percentage of the total\... q (for example) When the yield is 99%, the
  bucket number is p99.

<!-- -->

    public class LagHistogram {
        private static final int MAX = 1000;
        private final LongAdder[] buckets = new LongAdder[MAX + 1];
        private final LongAccumulator max = new LongAccumulator(Long::max, 0);
        private final LongAdder sum = new LongAdder();

        public LagHistogram() {
            for (int i = 0; i <= MAX; i++) buckets[i] = new LongAdder();
        }

        public void record(long lagMs) {
            int b = (int) Math.max(0, Math.min(lagMs, MAX));   // 负数归入 0，过大的值归入溢出桶
            buckets[b].increment();
            sum.add(lagMs);
            max.accumulate(lagMs);
        }

        public long[] snapshot() {                          // 当前每个桶的累计计数
            long[] s = new long[MAX + 1];
            for (int i = 0; i <= MAX; i++) s[i] = buckets[i].sum();
            return s;
        }

        public static long percentile(long[] counts, double q) {
            long total = 0;
            for (long c : counts) total += c;
            long target = (long) Math.ceil(q * total), acc = 0;
            for (int i = 0; i < counts.length; i++) {
                acc += counts[i];
                if (acc >= target) return i;
            }
            return counts.length - 1;
        }
    }

**Why use LongAdder?** Multiple consumer threads will call
simultaneously `The record `, however, shows that most messages have
similar lag , tending to cluster in a few buckets. If using a
regular\... `AtomicLong `, these popular buckets will be fiercely
contested by multiple threads. `LongAdder` Internally, the count is
distributed across multiple units, specifically designed for scenarios
where \"multiple threads frequently increment the same counter,\" which
is the same reason we use it to count the number of successes in A1 .

**Negative lag values are categorized into bucket 0** because clock skew
can cause small negative values. To accurately reflect clock skew, you
can separately count the number of negative values.

## every 10 seconds: Subtract from snapshot.

To plot the curve of lag over time, you need to know the distribution
within **each 10- second interval** , not just the cumulative overall
distribution.

**Do not reset the counter after printing** : between \"reading\" and
\"resetting\", other threads may record some values, which will be lost.

The correct approach is **to increment the counter, and each time you
print, subtract the previous snapshot from the current snapshot** .

    long [] prev = new long [ 1001 ]; 

    scheduler . scheduleAtFixedRate (() -> {
        long [] now = hist . snapshot ();
        long [] interval = new long [ now.length ] 
    ;​    for ( int i = 0 ; i < now . length ; i ++) interval [ i ] = now [ i ] - prev [ i ]; 
    prev = now ;     // Note: prev needs to be defined as a field or wrapped in an array so that it can be modified in the lambda.

        System . out . printf ( "lag p50= %d ms p99= %d ms %n " , 
    LagHistogram . percentile ( interval , 0.50 ), 
    LagHistogram . percentile ( interval , 0.99 )); 
    }, 10 , 10 , TimeUnit.SECONDS ) ;​

This scheduled task **executes only in one thread** , so `prev` Since
only this one thread reads and writes, there are no thread safety
issues. At the end of the test, the percentiles are calculated for the
last cumulative snapshot to obtain the overall distribution of the
entire test.

## Optional further optimization: Avoid parsing strings

`Instant.parse` This is the most expensive part of each message. If you
want to eliminate this, you can have the server add extra overhead in
AMQP when publishing messages . **headers** Put a millisecond number
inside:

    // server 发布时
    Map<String, Object> headers = Map.of("publishedAtMs", System.currentTimeMillis());
    AMQP.BasicProperties props = MessageProperties.PERSISTENT_TEXT_PLAIN.builder().headers(headers).build();

    // consumer 读取时
    long publishedAt = (Long) properties.getHeaders().get("publishedAtMs");

This eliminates the need to modify the message body format specified by
the job, consumer You can also read one directly. \`long\`, without
needing to parse the string. However, as calculated earlier,
`` `Instant.parse`... `` The cost itself is only about 1% , so this step
is not necessary.

## If you want to use an existing library

The industry commonly uses **HdrHistogram** . It\'s a library
specifically designed for recording latency distributions. Precision is
configurable, memory usage is fixed, and it also provides a\...
`Recorder` This class allows for thread-safe snapshotting of each time
interval, perfectly matching the requirement of \"printing every 10
seconds\" mentioned above. You can use it directly if you don\'t want to
implement it yourself. However, implementing this simple version only
requires a few dozen lines of code, and you can clearly explain each
line, which is perfectly sufficient for the assignment.

## User

Using buckets might have limitations in precision, right? Could we store
it directly in a list and then sort it? Could you calculate the memory
overhead for me?

## Claude

You\'re right, bucketing does indeed incur a loss of precision: values
within the same bucket are ultimately treated as the same value.
However, in our specific scenario, buckets with a width of **1ms
actually suffer almost no loss of precision** , for reasons I\'ll
explain below. Let\'s first address the memory issue.

## Memory overhead calculation

based on 500,000 records (regular testing) and 1,000,000 records (stress
testing):

  -------------------------------------------------------------------
  Storage method      Each element    500,000​         1 million
                      occupies                        
  ------------------- --------------- --------------- ---------------
  `long[]` primitive  8 bytes         About 4 MB      About 8 MB
  type array                                          

  `int[] `(lag) The   4 bytes         About 2 MB      About 4 MB
  value can be stored                                 
  using an int .                                      

  `ArrayList<Long>`   Approximately   About 14 MB     About 28 MB
                      28 bytes                        

  Bucket Histogram    Fixed size      tens of KB      tens of KB
  (1001) indivual                                     
  LongAdder)                                          
  -------------------------------------------------------------------

`ArrayList<Long>` Why 28 bytes? Let\'s take a 64- bit JVM with pointer
compression enabled by default as an example:

    The references stored in the List : 4 bytes for the 
    Long object itself: 12 bytes for the object header + 8 bytes for the long value = 20 bytes, which is 24 bytes after 
    8- byte alignment .               Approximately 28 bytes

Additionally, ArrayList When expanding the capacity, the capacity will
be increased to 1.5 times the original capacity, so at the moment of
expansion, the actual memory used may be higher.

Here\'s another interesting detail: Java It **caches Long objects with
values between -128 and 127** , and these cached objects are directly
reused during autoboxing. Since most of your lag is under 100ms , in
reality, most elements will only occupy 4- byte references. However,
this is just a coincidence and shouldn\'t be relied upon in your design.

**Conclusion: Using** `long[]` **or** `int[]` **If everything is saved,
the memory capacity is more than sufficient.** In contrast, consumer of
`skierVertical` There are approximately 400,000 entries. Each entry,
along with the String key and the overhead of the hash table, will take
up approximately tens of\... MB; even deduplication sets take hundreds
of MB. MB. In comparison, a few MB of lag data is negligible.

The overhead of sorting is also very small: using `Arrays.sort` Sorting
500,000 long strings takes only a few tens of milliseconds, and it is
only executed once at the end of the test.

## Why does a 1ms bucket operation result in almost no loss of precision?

The key point is that **the data you input itself is only millisecond
precise** :

    long lagMs = System.currentTimeMillis ( )​ - publishedAtMs ;

Since both timestamps are integers in milliseconds, the difference
between them, lag , must also be an **integer in milliseconds** .
Therefore, the value of lag can only be\... 0, 1, 2, 3\... **each
possible value corresponds to exactly one bucket . In this case,
counting by buckets and saving all the original values before sorting
will result in the same** percentile .

The only area where precision is lost is **the overflow bucket** : all
values greater than or equal to 1000ms are grouped into the same bucket.
There are two solutions to this problem: one is to expand the bucket
range, for example, to set it to 10 seconds, which would result in 10001
buckets, while still using only a few hundred milliseconds of memory.
KB; or, like the previous code, record the maximum value separately.

So, can we improve the accuracy to sub-millisecond levels? It\'s not
very meaningful. This is because lag is measured across two machines,
and the clock deviation between those two machines is already in the
sub-millisecond range. Even if you improve the measurement accuracy to
microseconds, most of the microsecond portion measured will just be
noise caused by the clock deviation.

## If you use an array to store the data, keep these two points in mind.

**1. Each thread uses its own array.** When multiple consumer threads
add elements to the same List simultaneously , locking is necessary. A
better approach is to give each consumer thread its **own dedicated
array , which is the same idea behind the \"thread confinement\"
principle we\'ve repeatedly used. Since we don\'t know in advance how
many messages each thread will process, we can pre-allocate a
sufficiently large capacity, or manually expand the list like** an
ArrayList if the capacity is insufficient .

**2. Summarize at the appropriate time.** When the test is finished,
**all consumer threads need to be stopped first** , and then their
respective arrays should be merged and sorted. If another thread reads
the arrays while a consumer thread is still writing, the problems we
discussed will occur. `volatile` The visibility issue mentioned earlier:
the data read may be incomplete.

## How to choose between the two methods?

  --------------------------------------------------------------
                       Save all original    Histogram of buckets
                       values and then      
                       sort.                
  -------------------- -------------------- --------------------
  Precision of the     accurate             It is equally
  final result                              accurate with
                                            millisecond integer
                                            inputs (except for
                                            overflow).

  Memory               As the number of     Fixed
                       messages increases   

  Interval statistics  It\'s rather         It\'s very simple,
  are printed every 10 complicated; it      just subtract the
  seconds.             requires saving      snapshots.
                       separately according 
                       to time intervals.   

  Difficulty of        Low                  Low
  implementation                            
  --------------------------------------------------------------

If you **only need to output an overall statistic at the end of the
test** , then storing all the values in an array and then sorting them
is the most intuitive approach, and you can definitely do it this way.

If you also want to **plot the lag over time** (which is very useful for
demonstrating whether the consumer is keeping up with production speed),
then a histogram would be more convenient.

You can also **use both simultaneously :** print interval statistics
every 10 seconds using a histogram, and calculate the precise overall
statistics at the end using an array. The combined memory overhead of
both is only a few MB.

## User

This is my consumer\'s pom.xml. How do I start it separately on EC2?

## Claude

your pom.xml file wasn\'t uploaded; I only saw the previous Postman
screenshot. You can paste the content directly into the chat, and I\'ll
check it line by line for you. Below are the general steps; you can
refer to them to see what\'s missing from your pom.xml .

## Overall process

    Local machine: Configure the packaging plugin in pom.xml → mvn clean package → Run the test on the local machine 
    ↓ SCP upload the JAR file . 
    EC2: Install Java → Run the test in the foreground → Configure. systemd allows it to run continuously in the background.

## Step 1 : Configure fat jar packaging in pom.xml

consumer depends on Libraries such as `amqp-client `and Gson .
Ordinary\... `mvn package` The generated JAR file **does not contain
these dependencies** , and it will throw an error when run on EC2.
`ClassNotFoundException `. Therefore, it needs to be used.
`maven-shade-plugin` Package all dependencies into a single JAR file.

exist `<build><plugins>` Add to:

    < plugin > 
    < groupId >org.apache.maven.plugins</ groupId > 
    < artifactId >maven-shade-plugin</ artifactId > 
    < version >3.5.1</ version > 
    < executions > 
    < execution > 
    < phase >package</ phase > 
    < goals >< goal >shade</ goal ></ goals > 
    < configuration > 
    < transformers >
                        <!-- Specifies the class containing the main method; java -jar will start the program from here --> 
    < transformer --> implementation= "org.apache.maven.plugins.shade.resource.ManifestResourceTransformer" > 
    < mainClass >com.skitrak.consumer.ConsumerApp</ mainClass > 
    </ transformer >
                        <!-- Merge the META-INF/services file; SLF4J 2.x depends on it to find the logging implementation --> 
    < transformer -- > implementation= "org.apache.maven.plugins.shade.resource.ServicesResourceTransformer" /> 
    </ transformers > 
    < filters >
                        < ! -- 
    Remove the signature file included with the dependency library; otherwise, a signature verification error may occur at runtime.--> 
    <filter> <artifact> * 
    : * 
    </artifact> <excludes> 
    <exclude> META - INF / 
    * . 
    SF </exclude> <exclude> 
    META 
    - INF 
    / 
    * 
    . DSA </exclude> <exclude> META - INF / * . 
    RSA </exclude> </excludes> </filter> </filters> </configuration> </execution> </executions> </plugin>

The parts that need to be modified according to your project:

- `<mainClass> : Change it `to include your consumer class . `main` The
  class containing the method **must have its full package name
  specified** .
- **Java version** : Check pom.xml `maven.compiler.release `(or `source`
  and The value of `target . EC2` The installed Java version **must be
  greater than or equal to** this value. For example, if you compile
  using version 17 , EC2\... The upper part is 11. It will report an
  error when running. `UnsupportedClassVersionError `.

## Step 2 : Package and test run on your local machine.

    mvn clean package

After completion, `target/` There will be two in the directory jar:

- `consumer-1.0-SNAPSHOT.jar `: **Contains all the dependent fat jars**
  ; this is the one uploaded.
- `original-consumer-1.0-SNAPSHOT.jar `: The original, dependency-free
  consumer 1.0. The jar file can be ignored.

The specific filename depends on what\'s in your pom.xml file.
`artifactId` and `version `.

**Before uploading, you must first test it on your local machine.**
`java -jar` **Run it once** to confirm that there are no problems with
the packaging:

    RABBIT_HOST = localhost java -jar target/consumer-1.0-SNAPSHOT.jar

something runs in an IDE **doesn\'t mean** the generated JAR file will
run, because the IDE will handle that automatically. classpath. As long
as it works on the local machine. `java -jar` It starts normally and can
connect to the local machine. RabbitMQ will generally not have any
problems when deployed on EC2 .

## Step 3 : Upload to EC2

    scp -i < key > .pem target/consumer-1.0-SNAPSHOT.jar ec2-user@ < consumer public IP > :~/consumer.jar

Rename it while uploading. `consumer.jar `, so that when updating the
version later, systemd will\... The configuration does not need to be
modified.

## Step 4 : Install on EC2 Java, and test run in the foreground.

    ssh -i <key> .pem ec2-user@ < consumer public IP > 
    sudo dnf install -y java-17-amazon-corretto-headless # Ensure the Java version number matches the one used during compilation
     -version

**Run it in the foreground once first** to confirm that it can connect.
RabbitMQ:

    RABBIT_HOST=<RabbitMQ 私有 IP> RABBIT_USER=skitrak RABBIT_PASS=<密码> java -jar consumer.jar

At this point, the logs will be printed directly to the terminal, so you
can see any problems immediately. Simultaneously, open the RabbitMQ
management interface and confirm that a connection from the consumer\'s
private IP appears on the Connections page , and the Queues\... The
Consumers column for each resort queue on the page displays\... 1. After
confirming that everything is correct, press\... `Ctrl+C` Stop it.

If the connection fails, the most common reasons are:

- **Connection timed out** : Security group `sg-rabbit` No permission
  `sg-consumer` Access port 5672 .
- **ACCESS_REFUSED** : Incorrect username or password, or the guest user
  was used.
- **Connection refused (Connection) refused)** : IP The address was
  entered incorrectly, or RabbitMQ is not running.

## Step 5 : Run continuously in the background using systemd

The problem with running it in the foreground is that once it\'s
closed\... SSH will cause the program to exit. Therefore, after
confirming that it can run normally, use the previous method.
`consumer.service` The file is managed by systemd .

    sudo cp consumer.service /etc/systemd/system/ 
    sudo systemctl daemon-reload 
    sudo systemctl enable --now consumer 
    sudo systemctl status consumer # Check running status 
    journalctl -u consumer -f               # View logs in real time

## When updating the code later

    # Local 
    mvn clean package 
    scp -i < key > .pem target/consumer-1.0-SNAPSHOT.jar ec2-user@ < IP > :~/consumer.jar 

    # EC2 
    sudo systemctl restart consumer

Note that **restarting the consumer will clear all state in memory** .
Therefore, do not update the code during formal testing, or use a new
dayID after each update for testing, as we explained when discussing
reconciliation.

you paste the pom.xml file here, I can help you check if the mainClass,
Java version, and dependency configurations are correct.

## User

    <?xml version="1.0" encoding="UTF-8"?>
    <project xmlns="http://maven.apache.org/POM/4.0.0"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
        <modelVersion>4.0.0</modelVersion>

        <groupId>skitrak</groupId>
        <artifactId>consumer</artifactId>
        <version>1.0-SNAPSHOT</version>

        <properties>
            <maven.compiler.source>11</maven.compiler.source>
            <maven.compiler.target>11</maven.compiler.target>
            <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        </properties>

        <dependencies>
            <dependency>
                <groupId>com.rabbitmq</groupId>
                <artifactId>amqp-client</artifactId>
                <version>5.21.0</version>
            </dependency>
            <dependency>
                <groupId>com.google.code.gson</groupId>
                <artifactId>gson</artifactId>
                <version>2.10.1</version>
            </dependency>
        </dependencies>
    </project>

## Claude

Your pom.xml file itself is correct, but it lacks configuration for
packaging into an executable JAR file , so the generated JAR file cannot
run on EC2 . I have modified it\... `pom.xml` Four changes were made:

**1. Added** `maven-shade-plugin `**(the most critical change).** It
will bundle all dependencies such as amqp-client and Gson into a single
package. Create a JAR file, and specify the class containing the main
method within the JAR file . `java -jar` Only then can it run directly.

**2. Use** `<main.class>` **This attribute specifies the startup class.
⚠️ You must modify this part.** I filled it out
`skitrak.consumer.ConsumerApp` It\'s just a placeholder; you need to
change it to your actual content. `main` **The fully qualified class
name** of the class that provides the method (package name plus class
name). If this is misspelled, a runtime error will occur.
`Could not find or load main class `.

**3. Log implementation added.** amqp-client depends on the SLF4J
logging interface, but if you haven\'t added a specific implementation,
it will print an SLF4J warning at runtime. (RabbitMQ) Client-side logs
(such as connection loss and auto-recovery information) will also be
discarded. I also stated that\... `slf4j-api` and `slf4j-simple `, and
make them **use the same version** . If only add `slf4j-simple `, Maven
We might choose another version provided by amqp-client . When
`the slf4j-api `version is incompatible, the logging implementation
still fails to work.

**4. Two minor improvements.**

- `source/target` Changed to `Release `. Only use `source/target` If you
  compile using JDK 17 , and your code unintentionally calls features
  only available in Java 17 \... The API will still compile
  successfully, but it will throw an error when running on Java 11.
  `(Release )` These kinds of problems will be detected during
  compilation.
- add `` `<finalName>consumer</finalName>  ``\` will directly output the
  filename. `The target/consumer.jar file makes `uploading and
  configuring systemd more convenient.

## The next steps

    # 1. Package and test run locally (if RabbitMQ is also on the local machine): 
    `mvn clean package 
    RABBIT_HOST = localhost java` -jar target/consumer.jar 

    # 2. Upload to EC2 using 
    scp -i <key> .pem target/consumer.jar ec2-user@ < consumer public IP > :~ 

    # 3. Install Java on EC2 (version 11 or 17 is fine, as long as it's not lower than 11) 
    sudo dnf install -y java-17-amazon-corretto-headless 

    # 4. First run it in the foreground to confirm it can connect to RabbitMQ 
    RABBIT_HOST =< RabbitMQ private IP > RABBIT_USER=skitrak RABBIT_PASS= <password> java -jar consumer.jar 

    # 5. After confirming there are no problems, use systemd to make it run continuously in the background (using the previously mentioned consumer.service file )

There\'s another prerequisite to confirm: has your code already passed?
`System.getenv("RABBIT_HOST")` Is it reading the RabbitMQ address? If
it\'s hardcoded in the code\... If the environment variable is set
`to localhost `, then the environment variable set in step 4 will have
no effect. The EC2 will still connect to its own local machine. RabbitMQ
failed to connect.

## User

Does RabbitMQ come with EC2?

## Claude

Not included. EC2 Essentially, it\'s an \"empty\" virtual machine with
only an operating system installed (like Amazon Linux 2023). Apart from
the basic tools that come with the system, you need to install other
software yourself, just like installing Java and Tomcat on A1 .

## AWS does offer managed services. RabbitMQ, but it cannot be used in this assignment.

AWS provides a called **Amazon MQ** The service can run and manage it
for you. RabbitMQ. But the assignment clearly states:

> *\"This assignment uses RabbitMQ --- not a managed queue\"* *\"Deploy
> RabbitMQ on a separate EC2 instance\"*

The purpose of this assignment is to teach you how to perform system
maintenance yourself. RabbitMQ handles connection and reconnection
manually. Also, Amazon MQ is not included in the free quota, so this
time I need to **install it myself** on an EC2 instance .

## Docker installation is recommended .

Install directly on Amazon Linux 2023 RabbitMQ requires a specific
version to be installed first. Erlang can cause problems if the version
is incompatible, which is quite troublesome. With Docker , the image
already contains everything needed:

    # 1. Install and start Docker 
    `sudo dnf install -y docker` 
    `sudo systemctl enable --now docker` 

    # 2. Run RabbitMQ (with management interface) 
    `sudo docker run -d` --name rabbitmq --restart unless-stopped \
      -p 5672:5672 -p 15672:15672 \
      -e RABBITMQ_DEFAULT_USER=skitrak \
      -e RABBITMQ_DEFAULT_PASS= < your password > RabbitMQ 
    :4-management 

    # 3. Confirm it's running: 
    `sudo docker ps` 
    `sudo docker logs rabbitmq` # Seeing "Server startup complete" indicates successful startup.

On the first run, Docker It will download the RabbitMQ image from the
internet; this may take one or two minutes. `--restart unless-stopped`
It can be guaranteed that RabbitMQ will work after this EC2 instance
restarts. It will also start automatically.

After starting, open it in your browser.
`Access http://<this ``EC2 ``'s public ``IP address>:15672 `and log in
as the skitrak user. Remember to open port 15672 for your IP address in
the security group first .

## If you don\'t want to use Docker

Alternatively, you can choose **Ubuntu** Use AMI to create this machine
EC2, and then following the RabbitMQ official website\'s installation
documentation, I added the official software repository via apt to
install Erlang and\... `RabbitMQ server `. Ubuntu has officially
supported installation steps, which are much smoother than installing
directly on Amazon Linux . However, this method involves more steps, and
subsequent execution\... `rabbitmqctl` The way commands are executed is
also slightly different from the Docker version.

Unless you have a specific reason, just use Docker . It\'s the simplest,
and you can run a project with the exact same commands when developing
locally. RabbitMQ keeps the local environment consistent with the EC2
environment.

## User

I have packaged consumer.jar locally. Could you please tell me my
consumer\... How to deploy EC2?

## Claude

Below are the complete steps from scratch; simply follow them in order.

## Step 0 : Before starting, confirm three things

1.  **The consumer \'s EC2 instance has been created** : t2.micro,
    Amazon Linux 2023, region \[region name missing\]. For us-west-2,
    the inbound rules of the security group must at least allow your IP
    address to access port 22 .
2.  **RabbitMQ security groups** `sg-rabbit` **Allow consumers to access
    port 5672 .** The source of inbound rules can be either the security
    group to which the consumer belongs or the consumer \'s private
    security group . IP address. This was missed; consumer\... The
    connection to RabbitMQ keeps timing out.
3.  **Write down the private IP address of the RabbitMQ machine** , in
    the format of\... The IP `address 172.31.xx `can be found on the
    instance\'s details page in the EC2 console.

## Step 1 : Upload jar (executes on local machine)

    scp -i < key > .pem target/consumer.jar ec2-user@ < consumer public IP > :~

## Step 2 : Log in and install Java ( running on EC2 )

    ssh -i < key > .pem ec2-user@ < consumer public IP > 

    sudo dnf install -y java-17-amazon-corretto-headless 
    java -version          # Confirm installation successful 
    (ls) -lh ~/consumer.jar # Confirm that the jar file has been uploaded

## Step 3 : Test run in the foreground

    RABBIT_HOST =< RabbitMQ private IP > RABBIT_USER=skitrak RABBIT_PASS= <password> java -jar ~ / consumer.jar

Please confirm the following:

- There were no errors in the terminal, but I can see the startup logs
  printed by your program.
- In the RabbitMQ management interface **Connections** The page
  displayed a connection from the consumer\'s private IP address .
- exist **Queues** Page, `resort.1` arrive `resort.10` of **Consumers**
  All columns have turned into 1.

After confirming that everything is correct, press `Ctrl+C` Stop it.

## Step 4 : Configuration systemd allows it to run continuously in the background.

Create service files directly on EC2 :

    sudo tee /etc/systemd/system/consumer.service > /dev/null << 'EOF'
    [Unit]
    Description=SkiTrak Consumer
    After=network-online.target
    Wants=network-online.target

    [Service]
    User=ec2-user
    Environment=RABBIT_HOST=<RabbitMQ 私有 IP>
    Environment=RABBIT_USER=skitrak
    Environment=RABBIT_PASS=<密码>
    ExecStart=/usr/bin/java -Xmx512m -jar /home/ec2-user/consumer.jar
    Restart=always
    RestartSec=5

    [Install]
    WantedBy=multi-user.target
    EOF

Remember to put the inside first. `<RabbitMQ ``Private ``IP>` and
`<Password>` Replace with the actual value.

Then start it:

    `sudo systemctl daemon-reload` # Instruct systemd to read the new service file. 
    `sudo systemctl enable --now consumer` # Start immediately and set to start automatically on boot. 
    `sudo systemctl status consumer` # Should display "active (running)".

## Step 5 : Verification

    journalctl -u consumer -f      # View logs in real time, press Ctrl+C to exit (the consumer will continue running in the background).

Double-check the RabbitMQ management interface to confirm that the
connection and the consumer for each queue are present.

**Test automatic restart** :

    sudo kill -9 $( pgrep -f consumer.jar )    # Force-kill the process: 
    sleep 6 
    sudo systemctl status consumer # It should now be active (running) again.

The assignment requirement of \"auto-restart on failure\" is implemented
through this mechanism, and a screenshot can be taken as evidence.

**To test automatic startup** : Reboot the instance in the EC2 console ,
and after it finishes booting, execute the command again.
`systemctl status consumer `.

## Commonly used commands

  --------------------------------------------------------------------------------------------------
  Purpose                        Order
  ------------------------------ -------------------------------------------------------------------
  Check status                   `sudo systemctl status consumer`

  View the last 100 lines of     `journalctl -u consumer -n 100`
  log.                           

  stop                           `sudo systemctl stop consumer`

  Restart                        `sudo systemctl restart consumer`

  After modifying the service    `sudo systemctl daemon-reload && sudo systemctl restart consumer`
  file                           
  --------------------------------------------------------------------------------------------------

## When updating the code later

    # Local 
    mvn clean package 
    scp -i < key > .pem target/consumer.jar ec2-user@ < consumer public IP > :~ 

    # EC2 
    sudo systemctl restart consumer

## If a problem occurs

  ------------------------------------------------------------------
  Phenomenon                       Possible reasons
  -------------------------------- ---------------------------------
  The log appeared                 The security group does not allow
  `Connection timed out`           consumers to access RabbitMQ port
                                   5672 .

  The log appeared                 The IP address is incorrect, or
  `Connection refused`             RabbitMQ is not running ( execute
                                   on the machine where RabbitMQ is
                                   located). `sudo docker ps`
                                   examine)

  The log appeared                 Username or password incorrect.
  `ACCESS_REFUSED`                 

  Connected to `localhost`         The code does not read
                                   `RABBIT_HOST` The environment
                                   variable is still hardcoded to
                                   localhost.

  `UnsupportedClassVersionError`   Java version on EC2 is lower than
                                   the version used at compile time.

  The status keeps switching       The program crashed immediately
  between activating and failed.   after starting.
                                   `journalctl -u consumer -n 100`
                                   View specific error information
  ------------------------------------------------------------------

## User

I want RabbitMQ and the consumer to start automatically on boot. How can
I achieve this?

## Claude

This needs to be examined from the perspective of both machines, and
their mechanisms are quite different.

## RabbitMQ: Requires two layers of protection

RabbitMQ runs in a Docker container, so for it to start automatically
after booting, two conditions must be met simultaneously:

    Machine boot → ① Docker service starts automatically → ② Docker automatically starts the RabbitMQ container

**① Enable Docker service to start automatically on boot:**

    `sudo systemctl enable docker` 
    `systemctl is-enabled docker` # If it outputs "enabled", the configuration was successful.

If you previously executed The step of `enabling --now docker `is
already complete.

**② Set a restart policy for the container.** Check the current
settings:

    sudo docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' rabbitmq

If the output is `unless-stopped` or `If it's always `, there\'s no
problem. If the output is\... `"No" `indicates that no code was added
when creating the container. `--restart` The parameters can be directly
modified; there is no need to recreate them.

    sudo docker update --restart unless-stopped rabbitmq

The difference between these two values is: `unless-stopped` This means
that if you have ever **executed it manually\...** If
`` you run `docker stop rabbitmq`  ``, it won\'t start automatically
after the machine reboots; and `always` This indicates that it will
start automatically regardless. Normally, it is used\...
`unless-stopped` That\'s it. This way, when you manually stop it, it
won\'t start up on its own.

### A pitfall to be aware of: data persistence

As long as it\'s **the same container** , the queue, users, and messages
will still exist after a restart. However, if you later **delete and
recreate** this container (e.g., by upgrading the version or modifying
parameters), all data will be lost, including all configurations except
for the user created via environment variables, as well as unconsumed
messages in the durable queue.

There are two reasons: First, the data is stored inside the container by
default; second, RabbitMQ stores data according to **the hostname ,
while** Docker generates a random hostname every time a new container is
created. Even if the same directory is mounted, the new RabbitMQ cannot
find the original data.

**to recreate the container** now as follows , with a fixed hostname,
and mount the data directory to a Docker volume :

    sudo docker rm -f rabbitmq
    sudo docker run -d --name rabbitmq --hostname rabbitmq \
      --restart unless-stopped \
      -v rabbitmq-data:/var/lib/rabbitmq \
      -p 5672:5672 -p 15672:15672 \
      -e RABBITMQ_DEFAULT_USER=skitrak \
      -e RABBITMQ_DEFAULT_PASS=<你的密码> \
      rabbitmq:4-management

After recreating, exchange The queue is automatically declared by the
code when server-v2 or consumer starts, so it doesn\'t need to be
manually rebuilt. If you used\... Remember to re-execute the policy.
`set_policy` Order.

## Consumer:systemd The enable function has already been achieved.

You wrote it in the service file `WantedBy=multi-user.target `, and
executed `` `systemctl enable`  ``will enable automatic startup on boot.
Please confirm:

    systemctl is-enabled consumer # output enabled

### However, there is a startup order issue here.

Both machines are powered on **simultaneously , but** the consumer and
RabbitMQ run on different machines, systemd. It\'s impossible to make
the consumer \"wait for RabbitMQ to start first\". Therefore, the
following situation is very likely to occur: consumer\... It has
started, but RabbitMQ is not ready yet, so the consumer connection
failed.

What happens then depends on your code:

- **If the connection fails, the program will exit directly** : systemd
  Will because `` Setting `Restart=always`  ``will restart RabbitMQ
  every 5 seconds until it is ready and the connection is successful.
  This will eventually allow the connection to be established, although
  the logs will show several failure records.
- **If the program doesn\'t exit after a connection failure, but instead
  gets stuck** : systemd It assumes the device is still running normally
  and won\'t restart it. This would leave it in a perpetually
  disconnected state, which is the worst-case scenario.

To make the first scenario more secure, it is recommended to refer to
the service file. `[Unit]` Add a line to the section:

    [Unit] 
    Description = SkiTrak Consumer 
    After = network-online.target 
    Wants = network-online.target 
    StartLimitIntervalSec = 0

systemd has a default \"startup frequency limit\": if a service restarts
too many times in a short period of time, systemd will\... It will give
up and not restart. `StartLimitIntervalSec=0` The purpose of this is to
remove the restriction and allow it to keep retrying. After
modification, execute:

    sudo systemctl daemon-reload

**A better approach is to handle it in the code.** We mentioned RabbitMQ
when discussing automatic recovery. The client\'s automatic recovery
function **only takes effect after the first successful connection** ;
the initial connection at startup needs to be handled manually.
Therefore, in the consumer \'s main method, a retry loop with
exponential backoff can be added to the first connection: if the
connection fails, wait a period of time before trying again until
successful. This way, even if RabbitMQ starts a few minutes late, the
consumer\... It can connect on its own without relying on systemd to
repeatedly restart it.

## verify

**Reboot simultaneously** in the EC2 console After both machines have
finished booting up:

    # On the RabbitMQ machine 
    , run `sudo docker ps` # The RabbitMQ container status is Up 

    # On the consumer machine, run 
    `sudo systemctl status consumer` # Active (running) 
    journalctl -u consumer -b          # Check the logs since this boot to see if there are any records of connection failures followed by successful retries.

Finally, open the RabbitMQ management interface and confirm that the
consumer connection has been restored, and that the Consumers column for
each queue is 1.

## Add one more point

The \"automatic startup\" mentioned here refers to the software running
automatically **after the instance starts . However, an** EC2 instance
itself will not restart automatically after being stopped ; you need to
manually restart it via the console. Start. After stopping and then
starting , the instance\'s public IP address will change, but the
private IP address will not change. Therefore, the RabbitMQ private IP
address configured in the consumer will remain valid and does not need
to be modified.

## User

Does Tomcat also need to start automatically on boot? Otherwise, do I
have to manually start each Tomcat instance one by one?

## Claude

Yes, Tomcat It also needs to start automatically on boot, which **is
especially important** for servers . There are two reasons:

- **The new machine cloned using AMI must have** Tomcat running
  automatically after startup ; otherwise, Tomcat will run automatically
  every time a new machine is started. For the server, you need to SSH
  in and manually start Tomcat.
- **ALB \'s health check** will only pass after Tomcat is running. If
  Tomcat doesn\'t automatically restart after one server is restarted ,
  it will remain marked as unhealthy by ALB and will no longer receive
  any traffic.

The specific steps depend on how you installed Tomcat in Lab 1 .

## First, confirm how you installed Tomcat .

    systemctl list-unit-files | grep -i tomcat

- **There is output** (e.g.) The \` `` tomcat9.service`  ``command
  indicates that you installed the service via a software package, and
  the system already includes the service file. You can execute it
  directly. `sudo systemctl enable --now tomcat9` That\'s it. Skip to
  the \"Verification\" section.
- **No output** : This means you downloaded a compressed file and
  extracted it to a directory (e.g., \...). This is a manual startup
  process using `` `/opt/tomcat`  ``, and most course labs use this
  installation method. In this case, you need to write your own service
  file, just like the consumer \'s approach.

## Write a systemd service for a manually installed Tomcat

### 1. First, stop the currently running Tomcat.

If you were previously in screen or using `startup.sh` If it\'s started
manually, you need to stop it first; otherwise, starting Tomcat with
systemd will fail because port 8080 is already in use.

    /opt/tomcat/bin/shutdown.sh

### 2. Confirm the owner of the file.

If you used it before `sudo` If Tomcat is started , then the logs,
temporary files, etc. it generates belong to\... root. When Tomcat
starts later as the ec2-user , It will fail to start due to insufficient
write permissions. First, change the owner of all files:

    sudo chown -R ec2-user:ec2-user /opt/tomcat

If your Tomcat is not available Replace all the paths in
`/opt/tomcat with your actual paths.`

### 3. Locate the Java installation path.

    dirname $( dirname $( readlink) -f $( which java ))) 
    # For example, the output is: /usr/lib/jvm/java-17-amazon-corretto.x86_64

### 4. Create service files

    sudo tee /etc/systemd/system/tomcat.service > /dev/null << 'EOF'
    [Unit]
    Description=Apache Tomcat 9
    After=network-online.target
    Wants=network-online.target
    StartLimitIntervalSec=0

    [Service]
    User=ec2-user
    Environment=JAVA_HOME=<上一步得到的 Java 路径>
    Environment=CATALINA_HOME=/opt/tomcat
    Environment=CATALINA_BASE=/opt/tomcat
    ExecStart=/opt/tomcat/bin/catalina.sh run
    Restart=on-failure
    RestartSec=5

    [Install]
    WantedBy=multi-user.target
    EOF

This uses `` Run `catalina.sh run`  ``instead of \`catalina.sh run\`.
The `difference `is: `startup.sh` After starting Tomcat , it will
immediately exit, allowing Tomcat to run in the background (systemd).
Additional configuration is required to track it; and `catalina.sh run`
This allows Tomcat to run in **the foreground** , systemd This process
can be managed directly by Tomcat. It can also restart correctly when it
crashes.

### 5. Start and configure automatic startup.

    sudo systemctl daemon-reload 
    sudo systemctl enable --now tomcat 
    sudo systemctl status tomcat # Should display active (running) 
    journalctl -u tomcat -f            # View real-time logs

## RabbitMQ environment variables be placed?

We previously put `RABBIT_HOST` Variables are written in `bin/setenv.sh`
Inside. `catalina.sh run` It will still read at startup. Since it\'s
setenv.sh, **no modifications are needed** ; you can continue using
setenv.sh .

You can also choose to write them into the service file. `Environment=`
**You can specify one of the locations** in \`setenv.sh\`, but only one
can be selected . If both locations are specified, use \`setenv.sh\`.
The values in the file will overwrite the values in the service file,
which can easily cause confusion when troubleshooting later.

## One issue that differs from the consumer\'s : Tomcat It will not restart due to failure to connect to RabbitMQ .

When the server and RabbitMQ are started at the same time, Tomcat may
start first and RabbitMQ may start later. This is a situation where we
are not prepared. However, the consequences in this case **are different
from those of** a consumer , and are more severe:

    Tomcat starts → deploys a WAR → contextInitialized connection to RabbitMQ fails, throwing an exception 
    → this WAR deployment fails, all endpoints return 404 
    → but the Tomcat process itself continues to run normally 
    → systemd considers everything normal and will not restart it 
    → this server will remain in the "Tomcat" state. "Running, but application unavailable" status

a consumer connection fails, the process will exit, and systemd will\...
It will restart; however, the Tomcat process will not exit, so systemd
\'s restart mechanism **is useless** .

The solution is to handle it in the code, and this aligns perfectly with
the \"readiness check\" we discussed in A1 when talking about health
checks. (check) is related to:

1.  `contextInitialized` **Instead of throwing an exception when the
    connection fails** , start a background thread that repeatedly
    attempts to connect using an exponential backoff mechanism. RabbitMQ
    will continue until successful. This way, the WAR will always be
    deployed successfully.
2.  **doPost​​**​`​` Return directly 503, the client will automatically
    retry.
3.  `/health` **Returning a 503 error before a successful RabbitMQ
    connection** will cause ALB to mark this server as unhealthy and
    temporarily stop forwarding traffic to it; once the connection is
    successful, \` `` /health` will be returned. `` After returning to
    200 , ALB It will then be automatically added back.

In this way, /health It will no longer simply return a fixed value. 200
doesn\'t just indicate whether the server is functioning correctly ; it
truly reflects whether the server is working properly. This is exactly
what we said in A1 : \"In Assignment 2, the health check will examine
whether the dependencies are functioning correctly.\"

## verify

Reboot this machine in the EC2 console . After the server has started:

    sudo systemctl status tomcat 
    curl -i http://localhost:8080/ < app > /health

After confirming there are no issues, **create the AMI . This way,** all
copies generated using this AMI will be processed. The server will run
automatically after startup. Tomcat will also automatically connect to
RabbitMQ.

## User

What\'s the next step in creating the image?

## Claude

Creating a mirror only requires a few clicks, but you must first ensure
that the first server is in a \"can be replicated\" state; otherwise,
every replicated machine will have the same problem.

## Step 0 : Pre-mirroring checks

Confirm the first unit item by item server:

- [ ] **After restarting , Tomcat can run automatically** .
  `` `curl http://localhost:8080/<app>/health` `` Return 200.
- [ ] **The WAR file represents the latest version** . An image is a
  snapshot; subsequent modifications to the WAR file will not be
  automatically synchronized to the copied machine.
- [ ] `setenv.sh` **It uses RabbitMQ \'s private IP address** , not the
  public IP address. IP address. The private IP address will not change
  after the instance restarts.
- [ ] **The serverId comes from the hostname, not hardcoded in the
  configuration . Otherwise,** all four replicated machines will use the
  same serverId.
- [ ] `/health` **Return a 503 error before the RabbitMQ connection
  succeeds** (the readiness check mentioned in the previous round). This
  is not mandatory, but it is strongly recommended to do so before
  mirroring.

Optional step: Clear Tomcat \'s old logs ( `rm /opt/tomcat/logs/* `).
This will start the logs from zero on the copied machine, making them
clearer to view.

## Step 1 : Create an image

1.  EC2 console → **Instances** → Select the first server.
2.  **Actions → Image and templates → Create image** .
3.  fill in:
    - **Image name** : for example
      `skitrak-server-v2-v1 . It's recommended to include the version number so that you can create `v2,
      v3, etc. , during future updates .
    - **Reboot instance** : **Keep this checked** (this is the default
      setting). When creating the image, AWS\... The instance will be
      restarted first to ensure that the data on the disk is in a
      consistent state. The instance will briefly stop service for one
      or two minutes and then automatically resume.
    - Storage settings: Keep the default settings.
4.  Click **Create image** .
5.  Open in the left menu **AMIs** , waiting state from `pending` Become
    `Available `, usually takes a few minutes.

The underlying image uses EBS snapshots for storage, which will incur a
small storage cost (a few cents per GB per month). After all testing is
complete, remember to check the AMIs page. **Deregister** This mirror
image, and to **Snapshots** The corresponding snapshot is deleted from
the page.

## Step 2 : Start the new server using the image.

1.  exist **AMIs** Select the previously selected image on the page →
    **Launch instance from AMI** .
2.  fill in:
    - **Name** : For example `server-v2 `.
    - **Instance type** : t2.micro.
    - **Key pair** : Select the same key as before.
    - **Network settings → Edit** :
      - **Subnet** : Select a subnet **within an Availability Zone
        enabled by your ALB** . This is very important: if a server
        resides in an Availability Zone that is not in the list of
        Availability Zones enabled by ALB , its status in the target
        group will be displayed as \[status missing\]. `unused `means it
        will never receive any traffic.
      - **Security group** : Select an existing one. `sg-server `.
    - The right side of the page **Number of instances** : fill in the
      blank **3** .
3.  Click **Launch instance** .

## Step 3 : Verify each new server

Wait for each instance **Status check** All are displayed as
`2/2 checks passed` after:

**① Direct access to each machine:**

    curl -i http:// < new machine 's public IP address > :8080/ <app> / health

The premise is `sg-server` Allow your IP address to access port 8080 .

**② Check the RabbitMQ management interface** . This step can verify
many things at the same time:

- **Connections** Page: You should be able to see from **4 different
  private IPs** The connection is one per server (plus one for each
  consumer ).
- **Queues** Page: In addition to the resort queue and dead letter
  queue, you should also see **The four names are as follows**
  `amq.gen-xxxx` **These are the queues** . They are temporary queues
  created by each server specifically for receiving leaderboard data.
  Click to open\... `leaderboard.fanout` this In the Exchange, you
  should see these four queues in the Bindings section. **This directly
  proves that each server is connected to the backplane.**

**③ Confirm that each serverId is different** : Send a message directly
to each machine. Swipe, then use the \"Get messages\" command in the
RabbitMQ management interface to view the message content and
`serverId.` The field should contain four distinct hostnames (in the
form of\...) `IP address 172-31-xx-xx `.

## Step 4 : Add them to the target group

EC2 Console → **Target Groups** → Select your target group → **Targets**
Tab → **Register targets** → Select the instances you want to add, enter
8080 as the port → Click **Include as pending below** → **Register
pending targets** .

Wait a minute or two, and the status of each machine should change.
**healthy** .

When testing a single instance, only one machine is kept in the target
group; when testing two instances, both instances are registered; when
testing four instances, all four instances are registered. There\'s no
need to stop other machines; simply **deregister them from the target
group** . (ALB) Then we will no longer forward traffic to them.

## When updating WAR later

After modifying the code, there are two ways to update it:

**Method 1: Redeploy one machine at a time** (suitable for debugging
phases, fast). A small script can be written and placed in\...
`/deployment` Table of contents:

    #!/bin/bash 
    # 
    deploy-servers.sh: Deploy the new WAR to all servers 
    KEY = <key> .pem WAR = target / <app> .war 
    SERVERS = " <IP1> <IP2> <IP3> <IP4>" 

    for ip in $SERVERS ; do
      echo "Deploying to $ip ..."
      scp -i $KEY $WAR ec2-user@ $ip :/opt/tomcat/webapps/
      ssh -i $KEY ec2-user@ $ip "sudo systemctl restart tomcat" 
    done

The IP address used here is from the public network. The IP address is
important because the script runs on your computer. Note that the public
IP address will change after the instance is stopped and then started
again , and the IP address in the script must be updated accordingly.

**Restart Tomcat** after deployment , instead of relying on Tomcat \'s
hot deployment. The reason is that your code contains\... `static` When
hot-deploying thread pools and RabbitMQ connections, older versions of
threads and connections may not be completely cleaned up, leading to
resource leaks or strange problems.

**Method 2: Recreate the image** (suitable after the version is stable).
Update the first server , then create a new image (e.g., \...). `(v2 `),
then use it to start a new instance and replace the old one.

During the debugging phase, method one is sufficient. Once the final
version is finalized and before formal testing begins, a new image can
be created as a clean baseline version.

## User

I\'m a bit confused about what this image means. We first created an
\"AMI\" using the instance that was already set up, and then created
three instances using this AMI. What is the relationship between these
three instances and the original instance?

## Claude

You can think of an AMI as a **mold** , or **a complete copy of a disk**
.

## What exactly is AMI ?

An EC2 instance can be roughly divided into two parts:

    A running instance 
    ├─Disk : Operating system, installed Java and Tomcat, WAR files, setenv.sh, systemd configuration... 
    └─Runtime status: Data in memory, running processes, IP address Address, instance ID...

**AMI saves only the portion of the disk** , essentially taking a
snapshot of the entire disk at a given moment. Data in memory and
running processes are **not** saved.

To launch a new instance using AMI , you first **copy this snapshot**
and use it as the disk for the new instance, then boot from that disk.

## What is the relationship between the four machines?

                     The original server ── ( the moment the AMI was created, the disk was copied) ──▶ AMI (Mold) 
    │ 
    ┌────────────────────────┼────────────────────────┐ 
    ▼ ▼ ▼
                                         New server 1, New server 2, New server 3

**They are completely independent \"brothers,\" not \"the main body and
its clones.\"**

- **At the moment the AMI is created** , the contents of the disks on
  all four machines are exactly the same.
- **Since then** , they have operated independently without affecting
  each other:
  - You modified the settings on the original machine. WAR, the other
    three **will not** change accordingly.
  - If you delete a file on the new server 1 , the other machines **will
    not** be affected.
  - You terminated the original machine, but the other three are still
    running normally, AMI. It is not affected by itself.
  - You deleted The three machines that have already been started using
    AMI will not be affected; however, they will no longer be able to be
    used to start new machines.

Using Java as an analogy, AMI\... It\'s somewhat like a **class** , and
instances launched using it are like\... `new` The resulting **objects**
: Each object has the same initial state when created, but each
object\'s fields change independently afterwards. However, this analogy
isn\'t entirely accurate: modifying the class\'s code will affect newly
created objects, while modifying the original machine will not affect
machines **subsequently started** using that AMI . This is because an
AMI is like a photograph that has already been taken and cannot be
changed. To include new modifications in a new machine, a completely new
AMI must be created.

## What are the same, and what are the different?

  -------------------------------------------------------------
  Same (contents on the disk)    Different (unique to each
                                 instance)
  ------------------------------ ------------------------------
  Java, Tomcat, and Docker       Instance ID
  installed .                    

  Deployed WAR                   Private IP and public IP

  RabbitMQ address in setenv.sh  **Hostname (** generated from
                                 private IP , for example)
                                 `IP- `172-31-5-23

  systemd service files and      Data in memory, running
  \"start automatically on       processes
  boot\" settings.               

  that can be used for SSH login Instance type, security group
                                 (can be reselected at startup)
  -------------------------------------------------------------

## This also explains several decisions we made previously.

Comparing this table with the previous steps, you\'ll find that they all
stem from this characteristic of AMI :

- **Why must Tomcat be configured to start automatically on boot?**
  Because AMI only copies the disk, **not the running processes** . When
  a new machine boots up, Tomcat\... It won\'t \"continue running\"; it
  can only start from scratch. And \"start at boot\"\... The Tomcat
  settings are stored on the disk, so they will be copied over.
- **Why does serverId need to be a hostname?** Because all the
  configurations on the disk are exactly the same, if the serverId is
  written in the configuration file, 4 The serverId of each machine will
  be exactly the same. However, the hostname is generated from each
  machine\'s own private IP address , so they are naturally different.
- **Why can RabbitMQ \'s address be copied directly?** Because all four
  servers need to connect to **the same\...** RabbitMQ, the
  configuration should be exactly the same.
- **Why do we need to deploy or recreate WARs on a machine-by-machine
  basis when updating them? AMI?** Because these machines are not
  connected, modifying one of them will not automatically sync to the
  others.

## User

(base) dlwlrma@dlwlrmadeMacBook-Pro 6650 % curl -i
http://\$ALB/ski-servlet/health HTTP/1.1 503 Service Temporarily
Unavailable Server: awselb/2.0 Date: Fri, 06 Nov 2026 02:25:47 GMT
Content-Type: text/html Content-Length: 162 Connection: keep-alive

503 Service Temporarily Unavailable

503 Service Temporarily Unavailable

(base) dlwlrma@dlwlrmadeMacBook-Pro 6650 %

## Claude

As can be seen from the response, **this 503 error was returned by ALB
itself, and the request never actually reached your Tomcat instance.**
There are two criteria for judgment:

- In the response header `Server: awselb/2.0 `indicates that this
  response was generated by AWS load balancers.
- The response body is a segment **The HTML** content is the default
  error page from ALB . Your own servlet, however, returns\... **JSON**
  , even your `/health` Returns if RabbitMQ is not connected. 503, the
  content will also be JSON.

Therefore, the problem lies between ALB and the target group, and has
nothing to do with your code.

## The meaning of ALB returning 503

ALB Return A 503 error typically means that **no target can be found to
forward the request.** The most common causes are listed below in order
of probability.

### reason 1: No instances are registered in the target group (most common)

EC2 Console → **Target Groups** → Select your target group → **Targets**
Tab. If the list is empty, it means you haven\'t registered an instance
yet. To register: **Register targets** → check the server instance →
enter the port number. **8080** → **Include as pending below** →
**Register pending targets** .

Many people skip the \"register target\" step when creating a target
group, intending to add it later, but end up forgetting about it.

### reason 2: The instance status is displayed as follows `unused`

If the instance has already been registered, but the status shows as\...
**When** you hover your mouse over the status bar, you\'ll usually see
the message: \" *Target is in an Availability Zone that is not enabled
for the load balancer* .\" This means that the availability zone where
this instance is located is not in the list of Availability Zones
enabled by ALB .

There are two solutions:

- Go to ALB → **Network mapping** → **Edit subnets and add the subnet of
  the availability zone where this instance is located.**
- Alternatively, restart an instance in an Availability Zone where ALB
  is already enabled.

### reason 3: The listener forwarded the message to the wrong target group.

ALB → **Listeners and rules** Tab → View **HTTP:80** The default action
of this listener is to ensure that it forwards the request to the target
group where you registered your instance. If you have created multiple
target groups, it\'s easy to select the wrong one, and forwarding to an
empty target group will result in a 503 error.

### reason 4: Status is `initial`

Newly registered instances will first enter `initial` Status: Awaiting
the results of the initial few health checks. Follow the job
requirements ( 30- second interval, two consecutive successful checks
required) from registration to becoming\... It takes at **least a
minute** to process. Just wait a moment and try again.

## At the same time, bypass ALB to directly test the server.

    curl -i http:// < server public IP > :8080/ski-servlet/health

- If **a 200 error is returned** , it means the server itself is working
  properly, and the problem is indeed with the ALB configuration.
- If **access is also unavailable** , first address the issues with the
  server itself (Tomcat). Is it running, WAR Was the deployment
  successful? Does the security group allow your IP address to access
  the site? 8080).

## You can refer to this table during future investigations.

ALB indicate different problems:

  --------------------------------------------------------------
  status codes         meaning              Common causes
  -------------------- -------------------- --------------------
  **503**              No target available  The target group is
                                            empty and the
                                            instance status
                                            is\... The listener
                                            was forwarded to the
                                            wrong target group.

  **502**              The connection was   Tomcat is not
                       established with the running (connection
                       target, but the      refused), or the
                       target\'s response   connection was
                       was problematic.     closed before
                                            returning a
                                            response.

  **504**              Connection to target `sg-server` **No
                       timed out            permission**
                                            `sg-alb` **Accessing
                                            port 8080** , or the
                                            server is processing
                                            too slowly.

  Returns a 503 error  The request has      your `/health` It
  in JSON format .     reached your code.   has been determined
                                            that the RabbitMQ
                                            connection has not
                                            yet been
                                            successfully
                                            established.
  --------------------------------------------------------------

Another situation worth noting: if the instance\'s state is\...
**unhealthy** , ALB It usually **won\'t** return. 503. When **all
targets** in a target group are unhealthy, ALB\... Will take \"fail\"
The \"open\" strategy still forwards the request to them, so what you
will see is the result returned by Tomcat , or\... 502, 504. Therefore,
we see ALB returning these errors. A 503 error can almost certainly mean
either \"no target registered\" or \"the target is not in an active
availability zone\".

Please check the Targets page for the target group. What is the current
status of the instance? If it shows\... Please also tell me the specific
reason why the status appears unhealthy when you hover your mouse over
it.

## User

The client-\>alb-\>server-\>broker workflow is basically complete. Could
you help me write a Python monitoring script using only Python\'s
built-in libraries? (RabbitMQ) Management interface port 15672, Username
Skitrak, the password is read from the environment variable RABBIT_PASS
, and the script accesses it via my public IP address on my Mac . It
makes one basic authentication request per second. In \`/api/queues\`,
sum the values of \`messages\`, \`messages_ready\`,
\`messages_unacknowledged\`, \`message_stats.publish_details.rate\`, and
\`message_stats.ack_details.rate\` for the 10 queues from \`resort.1\`
to \`resort.10\` (if the queue is idle , \`message_stats\` may not exist
and should be treated as 0 ). Also, separately record the values for the
dead-letter queue \`skitrak.dlqueue\` . Messages, one line is written to
each sample. Also prints results-v2/queue_timestamps.csv; command-line
parameter: \--host Required, --user default skitrak, --password Pick
RABBIT_PASS, --interval Default is 1 second, \--out Optional.

## Claude

`queue_monitor.py` It\'s written, using only Python\'s built-in
libraries. I tested it with a simulated management interface: the
summation result is correct, but the idle queue is missing.
`message_stats` When processed as 0 , the name is in the form of 0.
`amq.gen-xxx` The backplane temporary queue will not be counted, and
clear prompts will be given when the password is incorrect or no
password is set.

## usage

    export RABBIT_PASS = < your password > 
    python3 queue_monitor.py --host < RabbitMQ public IP address >

Other parameters: `--interval 0.5` Modify the sampling interval,
`--out xxx.csv` Specify the output file, `--password` This can override
the password in environment variables. (Press\...) `Ctrl+C` After
stopping, a summary will be printed: peak and average queue depth, and a
publish statement. The peak and average ACK rates, and the number of
messages in the dead-letter queue. These correspond exactly to the queue
metrics (peak/average depth, producer rate, consumer rate) that the job
requires to collect.

the CSV file is, in order: time, millisecond timestamp, seconds elapsed
since startup, messages, ready, unacked, and published. Rate, ack Rate,
number of messages in the dead-letter queue, and number of found resort
queues. Each line written is immediately flushed to disk, so even if the
script is forcibly terminated, the collected data will not be lost.

## Several places that need to be confirmed

**1. The name of the dead letter queue.** The name you gave is
`skitrak.dlqueue `, while we used it in our previous discussion.
`skitrak.dlq `. I wrote the name you gave me in the script; it\'s a
constant at the beginning of the file. `DLQ_NAME `. If the script
prompts this at startup\... The error
`message "dead-letter queue ... not found" `indicates that the name is
inconsistent with your actual declaration. You can fix this by modifying
the constant.

**2. The security group must allow your Mac to access port 15672 .** If
RabbitMQ \'s security group is only open to the server and consumer \...
5672, the script will keep printing `Request failed: timed out `.

**3. The rate is a smoothed value calculated by RabbitMQ .**
`publish_details.rate` and `ack_details.rate` This is the average rate
calculated by RabbitMQ itself based on data from the last few seconds,
not the precise difference between two samples. Therefore, even if you
sample once per second, the curve will appear smoother, and the rate
change will lag by a few seconds. It\'s perfectly sufficient for
plotting queue curves and comparing test results, but it\'s best to
mention this in the report.

**4. The ack rate depends on manual ack.** Your consumer is using manual
mode. ack, so this item will be displayed correctly. If a queue\'s
consumer uses `autoAck=true `, RabbitMQ It will not be counted. If ack
is entered, this column will always be 0.

Additionally, according to the assignment requirements, this script
should be placed in the repository. `/monitoring` Table of contents.

## User

There\'s a problem. For example, if I start the script 30 seconds in
advance, the test runs for 60 seconds, and then I press Ctrl again 30
seconds after it finishes\... The C interrupt likely diluted the average
depth.

## Claude

Yes, it will be diluted. In the original summary, publish The average of
ACKs only calculates \"active samples,\" but the average depth uses
**all samples** . Based on your description (first 30 seconds idle,
60\...), \... (30 seconds of testing followed by 30 seconds of idle
time), the remaining 60 seconds are all\... 0, the average depth will be
reduced to about half of the true value. (Updated) `queue_monitor.py`
This issue has been fixed.

## Modification method: Only count \"active windows\"

When summarizing, first find **the first** and **last** active samples,
and then calculate the average (depth, publish) of all samples **only
between these two.** Rate, ack (Use the same window for all rates, and
keep the same caliber).

A sample is considered \"active\" if: **there are messages in the queue,
or either the publish or ack rate is at least one message per second** .

I verified this using a set of simulated data (30) Idle for 2 seconds →
60 seconds of load, with a 3- second pause in between → several seconds
of rate decay → 24 seconds of idle):

    Active window: 30.0s ... 93.0s (63.0s, 64 samples) 
    Queue depth: peak 400, average 356.2 
    Naive average depth over all samples: 190.0 ← Algorithm before modification

## Two design trade-offs

**1. Short pauses during the test will be preserved.** For example,
there might be a second or two of no traffic between the warmup and main
phases. I chose to use the entire time frame \"from the first to the
last active sample\" instead of removing every idle sample. This pause
is part of the testing process, and removing it would actually make the
results appear better than they actually are.

**2. Only a small portion of the rate decay tail is retained.** RabbitMQ
\'s rate is calculated smoothly; it will not immediately drop to its
original value after the test. Instead of 0, it gradually decays over
several seconds. `ACTIVE_RATE = 1.0` This threshold will remove the tail
portion that decays to less than one message per second. However, a few
seconds are still counted in the window before that ( 4 seconds in the
example above), which will slightly lower the average. If you think the
impact is too great, you can increase this constant at the beginning of
the file, for example, to 50.

## Another minor issue was fixed.

During testing, I found that if the button **was pressed again during
the script\'s exit process\...** Ctrl+C would cause the summary to fail
to print due to the second interruption. Now, after receiving the first
Ctrl+C , the script will ignore subsequent interruptions, ensuring the
CSV file is closed properly and the summary is printed completely.

## The summary is simply a convenient way to view the results.

The CSV file still contains **all the original samples** , including
idle time periods, `elapsed_s` This column has also been retained.
Therefore, if you want to recalculate using a different time window when
writing your report, such as only counting the main phase, you can
directly use this column. `elapsed_s` The corresponding rows are
extracted and then calculated, without relying on the summary printed by
the script.

## User

When my client is not running, it can access RabbitMQ normally. However,
once it starts running, when I try to access RabbitMQ, it displays the
error: \"Error: could not connect to server since 2026-11-05 19:05:50.
Will retry at 2026-11-05 19:06:25\".

## Claude

This message comes from the RabbitMQ **management interface** : The page
in the browser requests data from the management interface on port 15672
every few seconds . If the request fails or times out, the page will
display this message and tell you the time for the next retry.

Therefore, it indicates that **the management interface became
unresponsive during the load test.** This does not necessarily mean that
RabbitMQ has stopped sending and receiving messages, AMQP (5672) The
port may still be working normally, but the part responsible for
statistics and display is overwhelmed. Your monitoring script should
also continue to print at this time. `Request failed: timed out `.

\"It works fine when there\'s no load, but becomes inaccessible once it
starts running,\" this is almost certainly true. **The machine hosting
RabbitMQ ran out of resources under load** .

## The most likely reasons

**1. The CPU is fully utilized, or the CPU credits have been exhausted
(most likely).** t2. There is only 1 microcontroller. vCPU. During load
testing, RabbitMQ The task involves routing thousands of messages per
second, persistent disk writing, and publisher operations.
Confirmations, as well as consumer submissions and ack. The statistics
and request processing of the management interface have relatively low
priority, CPU. When stressed, it becomes unresponsive first. If the CPU
credits are exhausted, leaving the machine with only 10% of its baseline
performance, the situation will be even worse.

**2. Insufficient memory.** The machine only has 1 GB of memory. If the
consumer can\'t keep up, the queue will start to back up, and RabbitMQ
will\... Memory usage can rise rapidly, potentially triggering **a
memory alert** (pausing the reception of all releases). In more severe
cases, Linux\... The OOM killer can even kill the RabbitMQ process
directly.

**3. Disk I/O.** Persistent messages, coupled with publisher confirms,
mean that RabbitMQ frequently writes data to disk and waits for the
write to complete. Small-capacity EBS disks, under continuous
high-frequency writes, experience high I/O\... Ability can also become a
bottleneck.

**4. Monitoring itself also increases the load.** Both the management
interface and the monitoring scripts are requesting the management
interface, `/api/queues` This request itself also requires RabbitMQ to
aggregate statistics from all queues. If the management page in the
browser and the monitoring script running once per second are running
simultaneously, the overhead will be cumulative.

## Troubleshooting during stress testing

When load testing is running and the management interface is
inaccessible, SSH Log in to the machine where RabbitMQ is located and
execute the following commands in sequence:

    top                       # Check CPU usage. Pay special attention to the third line, %st (steal).

- if `%Cpu(s)` near 100% means the CPU is fully utilized.
- if If `the st `**(steal) value is very high** (e.g., tens of
  percentage points), it means that the CPU credits have been used up
  and the virtual machine is being throttled by AWS .
- if `wa `**(I/O The high value of the \"wait\" parameter** indicates
  that the bottleneck is on the disk.

<!-- -->

    free -m                                                 # Remaining available memory 
    sudo docker stats rabbitmq --no-stream                  # Container CPU and memory usage 
    sudo docker exec rabbitmq rabbitmq-diagnostics alarms # Check if memory or disk alarms have been triggered 
    sudo docker exec rabbitmq rabbitmqctl list_queues name messages # View queue depth directly without going through the management interface

This last command is very useful: `rabbitmqctl` It can often still be
used even when it\'s inaccessible through the management interface,
without going through an HTTP interface. If the queue depth it displays
is **continuously increasing** , it means the consumer is not keeping up
with the production speed, and the problem will become increasingly
serious.

After the test is completed, you can also check:

    Use `sudo docker ps # STATUS` to check if the container has just been restarted. Then use 
    `sudo dmesg |` grep -i -E "oom|killed process"    # Check if any processes were killed by the OOM killer: 
    sudo docker logs rabbitmq 2 >& 1 | grep -i alarm # Whether a memory alarm has occurred.

Additionally, in the AWS console **CloudWatch** Inside, check the
performance of this instance during the test. `CPUUtilization` and
`CPUCreditBalance` Curve. If `CPUCreditBalance` It dropped to \[a
certain level\] during the test. 0 means the CPU credits have been
exhausted.

## Resolve based on the investigation results.

  -------------------------------------------------------------
  Investigation results          Solution
  ------------------------------ ------------------------------
  CPU usage is at full capacity, Replace RabbitMQ with a larger
  or stealing is very high.      instance (such as t3.small
                                 or\...) . (t3.medium). This is
                                 the most direct method, but it
                                 will incur costs. Remember to
                                 revert to the previous method
                                 or stop it after testing.

  Queue depth continues to       The problem is that the
  increase                       consumer can\'t keep up; it
                                 needs optimization. consumer
                                 (adjustment) prefetch, batch
                                 (ack), not RabbitMQ itself.

  an OutOfMemoryError (OOM)      First, check if the queue is
  occurred.                      backlogged. Also, Docker\...
                                 The container has no memory
                                 limit by default, but you can
                                 consider configuring a
                                 suitable memory threshold for
                                 RabbitMQ.

  I/O wait is high.              Persistent disk writing is too
                                 expensive; you can change the
                                 disk type in the EC2 console.
                                 gp3, or explain in the report
                                 that this is the cost of
                                 persistence.

  Resources are still available, Reduce the load from
  but the management interface   monitoring: Change the
  is not responding.             interval of the monitoring
                                 scripts to\...
                                 `--interval 5 `, during load
                                 testing, close the management
                                 page in the browser and only
                                 keep one monitoring method
                                 open.
  -------------------------------------------------------------

Another easily overlooked reason: **check if the channel is being
repeatedly created and closed.** During load testing (if access is still
available), or at the very beginning of the test, check the number of
Channels in the management interface . Normally, it should be a stable
number (the pool size per server , plus the backplane and consumer sizes
). If this number is constantly changing, or the total number is
continuously increasing, it indicates that the ChannelPool is not being
reused properly. Channels. Frequently creating and closing channels can
incur significant overhead in RabbitMQ .

## This is also valuable test data in itself.

**system metrics (CPU, memory, network)** during each test and
identifying bottlenecks. (RabbitMQ) The statement, \" t2.micro, under
load, exhausted its resources and even the management interface became
unresponsive,\" is a good example of bottleneck analysis. `top`
Screenshots, CloudWatch Recording the curves and changes in queue depth,
analyzing where the bottlenecks are in the report, and comparing the
effects after changing the instance type will be very convincing content
in the performance analysis section.

You can run it during the next load test. Tell me the results, and
we\'ll work together to determine the specific cause `.`

## User

I ran it for the first time with 512 threads, and then there seemed to
be a problem in the middle; there was no throughput for 30-150 seconds.
Can you guess what the problem might be? ===== Summary ===== samples :
308 (written to results-v2/run1_queue.csv) queue depth : peak 98, avg
19.0 over 154 active samples publish rate : peak 4104.8/s, avg 3192.9/s
over 154 active samples ack (consume) rate : peak 3971.2/s, avg 3156.6/s
over 154 active samples dead-letter queue : 0 messages at last sample
(base) dlwlrma@dlwlrmadeMacBook-Pro skitrak %

## Claude

Based on the data, this wasn\'t a \"capacity shortage\" issue, but
rather a system crash **lasting approximately 110 seconds, after which
the system fully recovered** . After recovery, the system stably
achieved a throughput of approximately 3650 transactions per second
using the same 512 threads , significantly higher than during the warmup
phase. Therefore, the system is capable of handling this load under
normal conditions; the problem occurred at **the very beginning of** the
main phase .

Below is my inference based on the data, and I\'ve included the
verification method at the end.

## Timeline

  --------------------------------------------------------------
  time                 Throughput           what happened
  -------------------- -------------------- --------------------
  0 -- 23s             Approximately 1400/s the warmup phase, 32
                                            One thread,
                                            everything is
                                            normal.

  23 -- 30s            533/s                the main phase, the
                                            concurrency suddenly
                                            increases from 32 to
                                            512.

  30 -- 140s           **Almost 0**         The system was stuck
                                            for approximately
                                            110 seconds.

  140-150s             313/s                Start recovery

  150 -- 270s          Approximately 3650/s Fully recovered and
                                            has remained stable.
  --------------------------------------------------------------

## Five clues in the data

**clue 1. During the freeze, no messages arrive at RabbitMQ.** Your
monitoring summary shows 308 Only 154 out of the samples were
\"active\". This roughly corresponds to the two normal operating times
before and after the freeze (approximately 25 seconds plus approximately
130 seconds). Meanwhile, the peak queue depth was only\... 98, the
average is 19, consumer I can keep up perfectly. Therefore\... Neither
**RabbitMQ nor the consumer is the cause of this problem** . The issue
lies upstream of RabbitMQ : either the request didn\'t reach its
destination. The server either received the request but got stuck before
or during the process of publishing the message.

**clue 2: The longest request took 150 seconds.** This indicates that a
request is **pending somewhere without receiving any response** , rather
than failing quickly. If the server could return quickly\... 503, the
client will retry soon, and no request will be pending for 150 seconds.

**clue 3: The duration of the freeze is close to the duration of the ALB
idle timeout.** If you set the ALB idle timeout to 120 seconds as we
discussed earlier , then this coincidence is noteworthy: if the client
**doesn\'t set** a response timeout, then the suspended request will
wait indefinitely until the ALB hasn\'t received a response from the
server after 120 seconds , at which point it will proactively return a
504 and disconnect. Thus, the duration of the freeze is determined by
the ALB \'s timeout.

**clue 4: The connection was reconnected 490 times, with an error
occurring in almost every main phase connection.** A total of 1002 new
connections were created , which is equivalent to 32 (warmup)+ about 480
(pool expanded to) 512)+ 490 (Reconnection). This indicates that during
the period of the freeze, **almost all ongoing requests simultaneously
encountered errors** , the connections were closed, and then all were
re-established. This perfectly matches the scenario of \"a large number
of requests being suspended and timed out simultaneously.\"

**clue 5: The leaderboard push was also delayed by about 100 seconds.**
The push delay P95 is 80 seconds, with a maximum of 107 seconds, which
is roughly the same as the duration of the freeze. During the freeze,
the consumer\... Cannot receive new A swipe would prevent the generation
of new leaderboards. Therefore, these severely delayed pushes likely
represent **leaderboards generated before the freeze occurred, only
being sent to the client after the freeze ended** . This indicates that
the server \... **WebSocket push was also stuck** , not just the HTTP
request for ingest .

## Speculation: The most likely reason

Put these clues together:

> At the start of the main phase, when the concurrency surged from 32 to
> 512 , server-v2\... It enters a \"no longer responding\" state: it
> neither processes HTTP requests nor pushes WebSocket messages. The
> suspended requests are not released until the ALB ( Application Not
> Responding) or the client times out, after which the server\... It has
> returned to normal.

for why the server is completely frozen, listed below in order of
probability:

**possibility A: Tomcat The JVM is running out of memory and is getting
caught in frequent garbage collection.** Without special configuration,
the JVM The maximum heap memory is limited to physical memory by
default. 1/4, t2.micro With only 1 GB of memory, which translates to
approximately 250 MB , 512 concurrent requests arrive simultaneously.
Each request requires object creation and serialization. JSON, coupled
with buffers for the RabbitMQ client and WebSocket , will likely push
heap memory close to its limit. At this point, the JVM will spend almost
all its time on garbage collection, and **the entire process will appear
to be frozen** : HTTP Both requests and WebSocket pushes will stop. They
will resume normal operation once the requests are released due to
timeouts and memory pressure decreases. This possibility is the only one
that can simultaneously explain \"HTTP\...\" \"Request stuck\" and
\"WebSocket\" The push notification also got stuck.

**possibility B: ChannelPool Tomcat is exhausted. All threads are stuck
on the waiting channel .** If the number of channels in the pool is much
less than Tomcat \'s 200 worker threads, and your `borrowChannel` The
one used is `` If `take()  ``\` is called (without a timeout), then all
Tomcat threads will queue up and wait. channel. If the return is not
found on certain abnormal paths. channel (e.g., not specified in)
`finally` If a closed channel is discarded without being replaced, the
pool will shrink, eventually trapping all threads. However, this
scenario doesn\'t quite explain the delay in WebSocket pushes, as push
functionality doesn\'t require Tomcat worker threads.

**possibility C: RabbitMQ The server connection is blocked .** RabbitMQ
blocks all connections that are publishing messages when a memory or
disk alert is triggered. `basicPublish` It will remain suspended until
the alert is cleared. The \"Management interface inaccessible\" issue
you encountered last time indicated that the RabbitMQ machine was indeed
experiencing resource constraints. However, the queue depth is very low
this time, so this possibility is relatively small.

## How to verify

**Step 1 : Check what the requests returned during the freeze (the most
useful step).** You already have
`The file results-v2/allswipes.csv `contains statistics on the period
during which the process was stuck (30 minutes). The number of various
status codes (between 1 and 140 seconds):

    awk according to your CSV format.
     -F, 'NR==2{t0=$1} NR>1 && ($1-t0)/1000>=25 && ($1-t0)/1000<=145 {print $4}' results 
    -v2/allswipes.csv | sort | uniq -c

  -------------------------------------------------------------
  result                         illustrate
  ------------------------------ ------------------------------
  A large number of **504**      The ALB wait for the server
                                 timed out, indicating that the
                                 server received the request
                                 but has not yet returned a
                                 response. Possibility A or B
                                 is supported.

  A large number of **503 (JSON) The server code checks if the
  (format)**                     channel cannot be borrowed, or
                                 if RabbitMQ is unavailable. It
                                 supports possibilities B or C.

  A large number of **502**      The server actively
                                 disconnected the connection,
                                 or Tomcat crashed.

  Numerous exceptions, or status The client\'s own timeout was
  codes like -1                  triggered, and no response was
                                 received.
  -------------------------------------------------------------

**Step 2 : Check the server logs.**

    journalctl -u tomcat --since "2026-11-05 19:00" --until "2026-11-05 19:15" | \
      grep -i -E "outofmemory|exception|blocked|timeout" | head -50

Pay close attention to: `OutOfMemoryError `(possibility) A)
`TimeoutException` Or the channel \'s logs might not be available
(possible). B) `connection.blocked` or
`AlreadyClosedException `(possible) C).

**Step 3 : Check the CloudWatch metrics.** In the EC2 console, view the
server instance\'s activity during this time period. `CPUUtilization `;
in ALB **Monitoring** View in the tab `HTTPCode_ELB_5XX_Count `,
`HTTPCode_Target_5XX_Count` and `TargetResponseTime `. If
`TargetResponseTime` The fact that the server remained unresponsive for
nearly 120 seconds during that period indicates that it did not respond
until the ALB timed out.

**Step 4 : Check the RabbitMQ logs.**

    sudo docker logs rabbitmq 2 >& 1 | grep -i -E "alarm|blocked"

C can be ruled out .

## Before the next test, it is recommended to make the following modifications.

1.  **Set a response timeout for the client** , such as 10 seconds. This
    way, even if the server freezes, the client can start retrying after
    10 seconds instead of waiting for the ALB \'s 120- second timeout.
    This greatly reduces the time wasted when the server freezes and
    makes it easier to pinpoint the exact time the problem occurred.

2.  **Enable GC logging on Tomcat and explicitly set the heap size** .
    `setenv.sh` Add to:

        export CATALINA_OPTS = "-Xms512m -Xmx512m -Xlog:gc*:file=/opt/tomcat/logs/gc.log:time"

    If the freeze occurs again, check\... `gc.log` This allows you to
    directly confirm or rule out possibility A.

3.  **test with 128 or 256 threads** to see if the freezing issue still
    occurs. If it doesn\'t occur with lower concurrency, then the
    problem is indeed related to the sudden increase in concurrency at
    the start of the main phase.

## Two issues I discovered incidentally

**1. The median push delay is -119ms indicates a significant clock skew
between your Mac and EC2 .** A negative number indicates that your Mac
\'s clock is more than 100 milliseconds slow compared to EC2 . This is
much larger than the usual deviation after NTP synchronization,
suggesting that your Mac may not have been synchronized for some time.
You can execute\... `sudo sntp -sS time.apple.com` Synchronize once,
then use the Cristian algorithm we discussed earlier to estimate the
deviation. Otherwise, the delayed data push will be severely affected by
this deviation.

**2. 37 swipes failed after 5 retries .** They all likely failed during
the freeze. If you plan to reconcile rankings, be aware that for some of
these requests, the server may have successfully published the message.
RabbitMQ simply failed to return the response to the client. Therefore,
the client perceives the reconciliation as a failure, even though the
consumer has actually already added it in. This results in a discrepancy
where the \"actual value is larger than the expected value,\" which is
the situation we discussed earlier regarding eventId .

do step 1 and tell me the statistical results of the status codes. That
should be enough to determine which possibility it is.

## User

Could it be a problem with my Mac connecting to ALB?

## Claude

That\'s very likely the case. And the monitoring data you posted
contains a key piece of evidence that supports this judgment: **during
the period when the system was frozen, the monitoring script itself also
lost connection.**

## Key evidence: The surveillance data was interrupted for more than two minutes.

Look at these two lines:

    2026-11-05T19:31:11.465, ..., elapsed 58.047 
    2026-11-05T19:33:18.125, ..., elapsed 184.710

**for a full 127 seconds** between these two lines, **there were no
records** . The script only writes a line when a request succeeds, so
during this time, its requests to the RabbitMQ management interface kept
failing (the terminal should have printed a string of errors).
`request failed: timed out `).

The request path for this script is **Mac → RabbitMQ \'s public IP
address** , without going through any intermediaries. ALB does not go
through a server.

## The timing is perfectly aligned

Align the monitoring time with the client\'s time:

  --------------------------------------------------------------
  Time point           Monitoring data      Client
  -------------------- -------------------- --------------------
  19:30:54             The publish rate     warmup begins
                       starts to appear,    
                       indicating that the  
                       client is starting   
                       at this point.       

  Approximately        **The last           The warmup phase
  19:31:15             successful sampling  ended (approximately
                       was at 19:31:11**    23 seconds), and
                                            **the main phase
                                            began, creating
                                            approximately 480
                                            new connections at
                                            once.**

  19:31 -- 19:33       **No data for 127    Throughput close to
                       seconds**            0

  19:33:18             Sampling recovery    140 to 150 seconds
                                            after the client
                                            session ended .
  --------------------------------------------------------------

The start and end times of the monitoring disconnection almost
completely coincide with the start and end times of the client freezing.

## What does this tell us?

**two unrelated programs** on your Mac access **two different AWS
addresses** (ALB). and RabbitMQ experienced problems at the same time,
and then recovered at the same time.

the server, ALB , or RabbitMQ ---cannot explain this phenomenon. For
example, even if the server is completely frozen, it won\'t affect the
monitoring script\'s direct access. RabbitMQ. The most reasonable
explanation is that **the problem lies in the segment they share, namely
your Mac and the network you are on.**

What triggers this is most likely the **approximately 480 TCP
connections established simultaneously at the start of** the main phase
. Common reasons include the following:

- **Home routers can\'t handle a sudden surge of new connections.** Home
  routers have small NAT connection tables, and many routers also have
  built-in protection features such as \" SYN flood attack prevention.\"
  A sudden influx of hundreds of new connections in a short period can
  fill up the router\'s NAT table or trigger its protection mechanism,
  temporarily dropping all new traffic and restoring normal operation
  after a minute or two.
- **VPN software running on a Mac .** If your Mac has a system proxy,
  VPN, or some proxy tool in TUN mode (which takes over all traffic)
  running, then these hundreds of client connections, as well as
  requests from the monitoring script, will all have to go through this
  software first. If it gets overwhelmed by a sudden surge in
  connections, all traffic will be blocked.
- **Wi-Fi network itself is unstable** , and this just happened to occur
  at this particular time. This is unlikely because the timing coincides
  too perfectly with the start of the main phase.

## I need to correct the two judgments above.

**1. In the previous round, I speculated that the server was stuck**
(JVM). Insufficient memory, ChannelPool (It could have been exhausted,
or RabbitMQ could have blocked the connection). Now, these possibilities
seem much less likely. The previous clues, using \"Mac\...\" The
explanation that \"the network was interrupted for about two minutes\"
is equally plausible, and even more reasonable:

- **The longest request took 150 seconds** : the request was suspended
  during the network outage until it timed out.
- **490 reconnections** : A network outage caused all currently active
  connections to be disconnected and need to be re-established.
- **The leaderboard push was delayed by approximately 100 seconds** :
  WebSocket The connection also originates from the Mac , and the pushed
  messages are backed up on the server side during the network outage ,
  only to be delivered to the client after the network is restored.

**2. Going back another time, you said, \"When the client is running,
the management interface becomes inaccessible.\" I suspect that the
RabbitMQ machine ran out of resources.** However, the browser you use to
access the management interface is also on this Mac . Considering this
data, it\'s very likely the **same issue** : your Mac \'s network is
experiencing problems while the client is running, rather than a problem
with RabbitMQ itself. The monitoring data also shows that when the
network is normal, the queue depth is only around ten or twenty rows.
The ack rate is basically the same as that of RabbitMQ. Both the
consumer and the consumer are in good health.

## How to confirm

**1. Check ALB \'s CloudWatch metrics (the most direct evidence).** In
the EC2 console , go to Load Balancers, select your ALB, and then go
**to Monitoring.** In the tab, adjust the time range to 19:30 . 19:36,
View **RequestCount** and **HTTPCode_ELB_5XX_Count** :

- If between 19:31 and 19:33 , **the RequestCount drops to close to 0,
  and no 5XX errors occurred** , indicating that the request never
  arrived. ALB, the problem lies on the client side.
- If a large number of 5XX indicates that the request has been received.
  If ALB fails but the server does not respond normally, then we need to
  go back to the possibilities from the previous round.

**2. In the next test, run a \"control group\" as well.** In another
terminal window, continuously ping an address unrelated to AWS :

    ping 8.8.8.8

ping also starts to time out when the client freezes , it indicates that
there is a problem with your local network.

**3. Check if a VPN or proxy software is running.** If so, turn it off
and test again to see if the problem persists.

## How to solve it?

**The most recommended approach is to run the client on an EC2 instance
on us-west-2 .** This approach achieves multiple benefits:

- **Completely bypass your local network** , including routers, proxy
  software, and Wi-Fi.
- **Throughput will increase significantly.** Now, with a stable
  throughput of 3650 requests per second , according to Little\'s Law,
  `512 / 3650 ≈ 140ms `. The average latency per request is 140
  milliseconds, most of which is network latency across the public
  internet. If clients are located in the same area, this latency will
  drop to a few milliseconds; what you measure is **the server\'s true
  processing capacity** . In the job, 1 machine and 2 machines\...
  Taiwan, 4 The comparative test of the two servers is precisely to
  compare the processing capabilities of the server side.
- **Clock skew will also be greatly reduced.** The -119ms clock skew
  discovered in the previous round mainly came from your Mac. Both EC2
  machines synchronize their time via Amazon Time Sync Service , with
  deviations typically within 1 millisecond, making push-delay data more
  reliable.

**If you still want to test on a Mac** , you can start the threads in
the main phase **gradually** , instead of starting 480 at once. For
example, add 16 threads every 100 milliseconds, and you can reach 512
within a few seconds . This way, new connections are established in
batches, preventing the router or proxy software from being overwhelmed
in an instant.

Finally, the monitoring script can continue to run on your Mac ; it only
sends one request per second and won\'t cause any problems in itself.
Just remember that if the client is still running on your Mac ,
monitoring will also be lost should your local network experience
issues.

## User

server-1 19.4% 23.96credit \[ec2-user@ip-172-31-47-254 \~\]\$ free -m
sudo grep -i -E "OutOfMemory\|SEVERE" /opt/tomcat9/logs/catalina.out \|
tail -20 total used free shared buff/cache available Mem: 913 358 76 0
477 421 Swap: 0 0 0 12-Sep-2026 03:00:45.431 SEVERE \[main\]
org.apache.catalina.valves.RemoteCIDRValve.setAllow .\*: The address
\[.\*\] is not valid 12-Sep-2026 03:00:45.458 SEVERE \[main\]
org.apache.catalina.startup.HostConfig.deployDirectory Error deploying
web application directory \[/opt/tomcat9/webapps/manager\] ALB: target
response time up to 120.1ms, connection error: No data available. Load
Balancers ELB 5XXs: 1

## Claude

This data essentially **rules out server- side issues** , further
confirming that the problem lies with your local network. Let\'s examine
each item:

  --------------------------------------------------------------
  index                Your data            illustrate
  -------------------- -------------------- --------------------
  server CPU​           19.4%                Far from reaching
                                            the limit

  CPU points           23.96                There\'s still some
                                            left; it hasn\'t
                                            been exhausted, so
                                            it hasn\'t been
                                            speed-limited.

  Memory               421 MB available     No memory pressure

  catalina.out         No OutOfMemory       The JVM did not
                                            experience an
                                            out-of-memory error.

  ALB \'s Target       **Up to 120.1        The server processed
  Response Time        milliseconds**       each request in a
                                            maximum of 0.12
                                            seconds and **never
                                            got stuck.**

  Target Connection    No data (i.e.) 0)    No failures occurred
  Errors                                    when ALB connected
                                            to the server .

  ELB 5XX              1                    one error during the
                                            entire test , which
                                            can be ignored.
  --------------------------------------------------------------

The most important thing is **The maximum value for Target Response Time
is only 120 milliseconds** . If the server is truly stuck for one or two
minutes, this metric should show a value close to 120 seconds because
the ALB will be waiting for the server \'s response indefinitely.
Therefore, the JVM memory shortage and ChannelPool issues I raised in
the previous round\... Exhausted, RabbitMQ The possibility of blocking
connections to these three servers can now be ruled out.

Combining the 127- second gap in the monitoring script\'s data, the
conclusion is clear: **during that time, requests were either never sent
from your Mac or were blocked before reaching the ALB . Both ALB and ALB
have been normal from beginning to end.**

If you want final confirmation, you can take another look at ALB .
**RequestCount** Indicator: 19:31 It should drop to near 19:33 . 0. This
directly indicates that the request did not reach the ALB during that
period .

## This set of data also reveals another thing.

When your system is running stably, it can handle approximately 3650
requests per second, while the server \'s CPU usage is less than \[a
certain percentage\]. 20%. According to Little\'s Law:

    512 threads / 3650 messages per second ≈ 140 milliseconds (average latency per request)

**The slowest** server processing time, according to ALB statistics , is
only 120 milliseconds, with an average of just a few milliseconds. This
indicates that most of those 140 milliseconds are spent **on public
network transmission between your Mac and us-west-2** , rather than on
the server .

In other words, **the bottleneck you\'re measuring right now is your
home network, not your system.** The server has a lot of headroom.
That\'s why I recommend running the clients on EC2 : only then can 1
Taiwan, 2 Taiwan, 4 A comparison between multiple servers is only
meaningful. Otherwise, no matter how many servers there are\... The
throughput of servers will be limited by the same network latency, so
the test results will be similar.

## placing the client on EC2 , pay attention to the following:

The client itself also requires resources. 512 One thread, plus
recording data for each request and writing\... In CSV mode, on a
t2.micro server with only one vCPU , **the client itself may become a
new bottleneck** . Therefore:

- At runtime, on the client machine\... `top` Let\'s observe. If the CPU
  stays close to\... 100% indicates the bottleneck is on the client
  side, requiring a larger instance (such as t3.medium or\...).
  c5.large, stop immediately after testing, very low cost).
- This also means you need to readjust your thread count. When the
  client and server are in the same region, network latency is low, and
  64 or 128 threads may be sufficient to achieve high throughput. The
  job requires testing with 64, 128 , 256, and 512 threads, which is
  perfect for finding the new optimal values.

## Severe entries in the log\...

The times of these two error messages are: **September 12th is**
unrelated to this test. These data come from the Tomcat **manager you**
configured in Lab 1 **.** Application: `RemoteCIDRValve` What is needed
is an address in CIDR format (e.g.) `0.0.0.0/0 `), while the
configuration specifies a regular expression. `The .* `symbol indicates
that the manager application deployment failed. This does not affect
your\... `ski-servlet `. If you don\'t use the manager application, you
can ignore this; if you want to fix it, then\...
`webapps/manager/META-INF/context.xml` That line `allow` Change the
address to CIDR format.

## User

How much would it cost to leave ALB (Allowed Burst) untouched for two
months? My total credit limit is 50 yuan.

## Claude

Based on the standard price estimate of US-West-2 , it would cost about
\$ **47 for two months , which would almost use up your \$ 50 credit
limit** , so it is not recommended to keep it indefinitely.

## Components of the fee

ALB \'s cost consists of three parts. The first two parts are the price
of ALB itself: in the eastern United States, ALB\... The hourly rate is
\$ 0.0225 , equivalent to approximately \$ 16.43 per month , plus an
additional \$ 0.008 per LCU per hour . (US-West-2) Prices are generally
the same as in the Eastern United States, but it\'s best to check with
the AWS pricing calculator. The third part is the public IP cost, which
many people overlook: each public IPv4 address costs \$ 0.005 per hour ,
and each public load balancer has one in each Availability Zone. Your
ALB has at least two Availability Zones enabled , so you\'ll have two
public IPs.

Two months is approximately 1464 hours:

  --------------------------------------------------------------
  project              calculate            cost
  -------------------- -------------------- --------------------
  ALB hourly rate      1464 hours × \$      Approximately US\$
                       0.0225               32.9

  2 public IPv4        2 × 1464 hours × \$  Approximately US\$
  addresses            0.005                14.6

  LCU (Limited Unit)   Almost during free   Less than \$ 1
                       time 0, only         
                       incurring a small    
                       fee during the few   
                       hours of load        
                       testing.             

  **total**                                 **Approximately \$
                                            47 to \$ 48**
  --------------------------------------------------------------

The key point is that **ALB will charge by the hour even if there is no
traffic,** and will continue to charge as long as it exists.

the 50 yuan you mentioned is in RMB, then you will exceed the limit in
less than a month.

## Actually, you don\'t need to leave it for two months.

Today is November 5th , and the deadline for the assignment is\...
**November 8th , only** 3 **days** left . Holding ALB until the
deadline, approximately 72 hours × (0.0225 + 0.01) USD ≈ **\$ 2.30 ,
is** perfectly acceptable.

**Recommended approach** : Keep it until you\'ve completed 1 or 2 units.
Taiwan, 4 All tests on the server and screenshots (ALB) (Monitoring
metrics and request distribution). **Delete ALB immediately after
submitting the job** .

If the subsequent assignments (such as Assignment 3 or\...) 4) Also
needed ALB can be recreated later; it should only take about 5 minutes.
**Target group (target The group itself is free** and can be retained.
When recreating an ALB , you can simply select it. The configuration of
health checks and sticky sessions does not need to be reconfigured.

## Don\'t forget the other resources that are also continuously charging fees.

Apart from ALB, there are several other resources in your account that
are also costing money:

- **Running EC2 instances** : If your account is no longer within your
  free quota, it costs approximately \$ 0.0116 per hour per t2.micro
  instance. 6 Running multiple machines simultaneously would cost
  approximately \$ 100 over two months . Furthermore, each running
  instance has public internet access. IP addresses will also be charged
  separately according to the prices listed above. **These instances
  must be stopped when not testing.**
- **EBS disks** : Even after the instance is stopped , **the disks will
  still be charged** , approximately \$ 0.6 to \$ 0.8 per month for each
  8 GB disk . 6 The machines together cost a few dollars per month, a
  small amount, but they won\'t stop automatically. Only Only by
  terminating the instance will its disk be deleted.
- **AMI snapshots** : As long as the image exists, its underlying
  snapshots will continue to be charged. Once you\'re sure you no longer
  need it, remember to deregister the image first, and then delete the
  corresponding snapshots.
- **Elastic IP not bound to an instance** : If you have created an
  Elastic IP but have not used it, it will still be charged hourly.

## Two things you can do right now

1.  **To check how much you\'ve already spent, go to the** AWS console →
    **Billing and Cost Management** → **Bills** . Also check\... **Free
    Tier** On this page, check how much free credit you have left in
    your account. The rules for free credit vary depending on when the
    account was registered. Some earlier registered accounts have 750
    hours of free load balancer credit per month for the first year
    after registration, meaning ALB \'s hourly fees are free.
2.  **Set up a budget alert.** exist **Budgets** Create a budget on the
    page, say \$ 30 , and set it to send you an email notification when
    it reaches 80% . This way, you\'ll receive a reminder before the
    expenses spiral out of control, even if you forget to delete a
    resource.

## User

Why was the school\'s network protection activated? What did our client
do?

## Claude

From a network administrator\'s perspective, your client\'s behavior at
that moment was almost identical **to that of a compromised computer
launching a DoS attack** . Let\'s first discuss what the client
specifically did, and then explain why the school network reacted
accordingly.

One point needs to be clarified first: I cannot directly see the school
network configuration; the following is an inference based on the
observed phenomena. However, the fact that \" **all** traffic to this
computer suddenly stopped for about two minutes and then automatically
resumed\" is very consistent with the common campus network mechanism of
\"automatically blocking abnormal hosts for a period of time.\"

## From a network perspective, what did your client do?

**1. Approximately 480 new connections were initiated in less than a
second . During the** warmup phase, there were only 32 threads and a
total of 32 connections; everything proceeded normally. (main) At the
beginning of the phase, 512 Several threads started almost
simultaneously, each requiring a connection, causing the connection pool
to expand from 32 to 512 connections in an instant. From the network
device\'s perspective, this means that this computer sent hundreds of
TCP connection establishment requests (SYN requests) to the same port
and destination address within one second. (Bag). This is exactly\...
Typical characteristics of **a SYN flood attack** .

**2. Continuously send a large number of requests to the same target.**
During normal operation, there are 1400 to 3600 HTTP requests per second
, all originating from the same computer and destined for the same
address. This appears to network devices as\... **HTTP flood attack** .
A normal user using a browser sends at most a few dozen requests per
second.

**3. It maintains over 500 long-lived connections simultaneously** :
**512 HTTP** connections and 10 WebSocket connections . Many campus
networks limit **the number of concurrent connections per device**
(commonly ranging from several hundred to around a thousand), partly to
prevent abuse and partly to protect the connection table of the NAT
device from becoming full.

**4. A sudden connection may occur again during a retry.** Once the
network is blocked, hundreds of threads will **simultaneously time out
and then** retry at the same exponential backoff interval . Each round
of retrying generates a burst of hundreds of new connections. This is
how the 490 reconnections you see occur. If the network device\'s
blocking rule is \"extend the blocking time upon detecting an anomaly,\"
this synchronous retries could potentially extend the blocking period
even further.

## Why did the school network react to this?

One thing that university network administrators are particularly
worried about is that **computers on campus could be infected with
viruses and become part of a botnet, launching attacks from outside.**
If this happens, the school\'s IP addresses may be blocked by external
services, and the school may face complaints as a result. Therefore,
campus network firewalls and intrusion detection systems typically:

- **Limit the number of new connections each device can establish per
  second** ; once this limit is reached, discard any additional
  connection requests.
- **Limit the number of simultaneous connections each device can
  maintain** ; once this limit is reached, no new connections can be
  established.
- **Detect traffic patterns resembling DoS attacks** . Once detected,
  **temporarily block all traffic to the device** and automatically
  unblock it after a period of time (e.g., a few minutes).

The third scenario best matches your situation: not only did the request
to ALB fail, but the request from the monitoring script to access
RabbitMQ also failed, even though the IP addresses of these two targets
are different. This indicates that **all outbound traffic from your
computer** is being blocked , not just a single connection.

Essentially, stress testing is a **DoS attack you launch on your own
system** , only the target is your own server. Network devices can\'t
discern your intent; they can only see the traffic patterns.

## What should be done?

**Best practice: Run the client on EC2 .** In this way, all load testing
traffic is transmitted within AWS and never passes through the school\'s
network. From AWS \'s perspective, load testing resources within your
own account is permitted. Furthermore, as we analyzed earlier, deploying
to EC2 ensures that the measured traffic reflects your server\'s true
processing capacity.

**If you still need to test on the school network for the time being** ,
at least do the following:

- Instead of starting 480 threads all at once **,** start the threads in
  **the main phase gradually , for example,** by adding 16 threads every
  100 milliseconds .
- **Limit the number of concurrent connections** , for example, to no
  more than 128 .
- **Adding random jitter to the retry backoff time** staggers the retry
  times of different threads, preventing a large number of simultaneous
  reconnections. This is the same principle as the \"thundering herd
  effect\" we discussed in A1 regarding WebSocket reconnections.

In addition, **try to avoid repeatedly triggering the campus network\'s
protection mechanism.** Many schools\' network usage policies explicitly
prohibit high-intensity traffic activities such as stress tests on the
campus network. If a device is repeatedly detected with abnormal
traffic, it may be blocked for a longer period or even reported to the
network administration department. If you still need to conduct such
tests on the campus network, you should consult your school\'s IT
department first.

## User

Why can\'t I connect to ALB after reopening three instances today? It
returns a 404 error. (base) dlwlrma@dlwlrmadeMacBook-Pro 6650 %
ALB=skitrak-alb-865987688.us-west-2.elb.amazonaws.com (base)
dlwlrma@dlwlrmadeMacBook-Pro 6650 % echo \$ALB
skitrak-alb-865987688.us-west-2.elb.amazonaws.com (base)
dlwlrma@dlwlrmadeMacBook-Pro 6650 % curl -i
http://\$ALB/ski-servlet/health HTTP/1.1 404 Date: Sat, 07 Nov 2026
23:27:56 GMT Content-Type: text/html;charset=utf-8 Content-Length: 774
Connection: keep-alive Set-Cookie:
AWSALB=ck+l+VLyv36xLpDTwZXx3299aY/IYg3TkYrethSlsJZatuRbutI6m6CoJcGyKYcPi+0ZRoCcY2//1ZglqviYPQLQE0Ocf0L17+LNuUJbF+TSD5cDqfC6Ru9ogHjc;
Expires=Sat, 14 Nov 2026 23:27:56 GMT; Path=/ Set-Cookie:
AWSALBCORS=ck+l+VLyv36xLpDTwZXx3299aY/IYg3TkYrethSlsJZatuRbutI6m6CoJcGyKYcPi+0ZRoCcY2//1ZglqviYPQLQE0Ocf0L17+LNuUJbF+TSD5cDqfC6Ru9ogHjc;
Expires=Sat, 14 Nov 2026 23:27:56 GMT; Path=/; SameSite=None
Content-Language: en

\<!doctype html\>

HTTP Status 404 -- Not Found

HTTP Status 404 -- Not Found

Type Status Report

Message The requested resource \[/ski-servlet/health\] is not available

Description The origin server did not find a current representation for
the target resource or is not willing to disclose that one exists.

Apache Tomcat/9.0.121

(base) dlwlrma@dlwlrmadeMacBook-Pro 6650 %

## Claude

This situation is different from the previous 503 error; this can be
seen from the response content: **the request has already passed
through. ALB, arrived. Tomcat, but it can\'t be found in Tomcat.**
`/ski-servlet` **This application.**

## What can we glean from the response?

- **The response contains** `Set-Cookie: AWSALB=... `: This is a sticky
  session added by ALB when forwarding requests. The cookie indicates
  that ALB did indeed forward the request to a certain server.
- **The response body is Tomcat \'s error page, and the last line
  is\...** `Apache Tomcat/9.0.121 `: This indicates that Tomcat itself
  is running and has returned this response.
- **The error message is**
  `The requested resource [/ski-servlet/health] is not available `:
  Tomcat The path could not be found. (The entire\...) `/ski-servlet`
  None of them exist, and not just\... `/health` It cannot be found,
  which means **The WAR file was not successfully deployed** .

By the way, why would ALB forward a request to an \"unhealthy\" machine?
server? That\'s exactly what we mentioned earlier. **fail open**
Mechanism: Health check visit `/ski-servlet/health` You will get 404
error, so all servers are deemed unhealthy; and when **all** targets are
unhealthy, ALB\... The request will still be forwarded to them.
Therefore, what you see is the response from Tomcat . It returned a 404
error, instead of the 503 error returned by ALB itself .

## Most likely reason: After restarting Tomcat Start before RabbitMQ

You restarted three instances today; I\'m guessing it was RabbitMQ,
consumer , and\... This is precisely the risk we mentioned when
discussing Tomcat\'s automatic startup:

    Three machines are powered on simultaneously 
    → Tomcat on the server starts up relatively quickly and begins deploying the WAR 
    → contextInitialized attempts to connect. RabbitMQ, but the RabbitMQ container is not ready, connection failed, throwing an exception 
    → the entire WAR deployment failed, /ski-servlet All paths under this directory return a 404 error 
    → The Tomcat process itself is still running normally and will not retry deployment, thanks to systemd. It won't restart 
    ; even if RabbitMQ is already running, this server will remain in a 404 state .

## How to confirm

Log in to the server via SSH and execute the following commands in
sequence:

**1. Confirm that the WAR file is still there:**

    ls -l /opt/tomcat9/webapps/

You should be able to see `ski-servlet.war `, and the extracted files
`ski-servlet/` Directory. If even the WAR file is missing, that\'s a
different problem and requires redeployment.

**2. Check the reason for the deployment failure:**

    sudo grep -n -E "SEVERE|startup failed|listener" /opt/tomcat9/logs/catalina.out | tail -20
    sudo grep -n -A 20 "SEVERE" /opt/tomcat9/logs/localhost.$(date +%Y-%m-%d).log | head -60

If my guess is correct, `catalina.out` Similar things will appear there
`One or more listeners failed to start` and
`Context [/ski-servlet] startup failed due to previous errors` **The
specific exception stack trace** is usually recorded in a second file .
`localhost.date.log` Inside, you will see
`The exception sending context initialized event to listener instance of class [...] `is
followed by RabbitMQ connection failure exceptions, such as\...
`ConnectException: Connection refused `.

Note that the date in the log file name uses the server \'s local
timezone. If a file with today\'s date cannot be found, use\...
`ls /opt/tomcat9/logs/` Check what files are included.

**3. Confirm whether the server can be connected now. RabbitMQ:**

    timeout 3 bash -c '</dev/tcp/<RabbitMQ Private IP>/5672' && echo "5672 open" || echo "cannot connect"

If displayed If
`a connection fails , execute the command on the `RabbitMQ machine . Use
`` `sudo docker ps`  ``to check if the container is running. If the
container is not running, use\... `sudo docker start rabbitmq` Start it
and check its restart policy (which we mentioned earlier when discussing
automatic startup). `docker update --restart unless-stopped rabbitmq `).

## Quick Repair

confirming that RabbitMQ can connect, restart it on **each** server .
Tomcat, make it redeploy WAR:

    sudo systemctl restart tomcat 
    sleep 15 
    curl -i http://localhost:8080/ski-servlet/health

returning to 200 , wait another minute or two for the ALB health check
to pass (the status in the target group changes to 200). (healthy), then
re-access via ALB .

## Long-term repair

The deadline is tomorrow, so I\'ll manually restart now. For Tomcat,
just make sure the tests run. However, this problem might reappear every
time you restart the machine, so when you have time, you should still
modify the code according to the method we discussed earlier.

- `contextInitialized` When failing to connect to RabbitMQ , **do not
  throw an exception** . Instead, start a background thread and retry
  repeatedly with exponential backoff until the connection is
  successful.
- Before the connection is successful, `/health` and `doPost` All
  returned a 503 error.

In this way, regardless of the order in which the machines are started,
the server They will eventually connect automatically. RabbitMQ, ALB It
will also be added back automatically once it is ready.

If the reason shown in the logs is different from what I suspect,
then\... `localhost.date.log` Send me that unusual section, and we\'ll
look at it together.

## User

That\'s true. I\'ll just restart Tomcat. Now I want to move the client
to EC2, but the school network doesn\'t allow stress testing.

## Claude

Yes, switching to EC2 is the most suitable approach. The deadline is
tomorrow, so I\'ll explain the fastest route below.

## Step 1 : Select the client instance type

The client needs to run 512 threads, and also record the data for each
request and write\... CSV has certain requirements for both CPU and
memory. (t2.micro) With only 1 vCPU and 1 GB of memory, **the client
itself is likely to become the bottleneck** , causing you to measure the
upper limit of the client rather than the upper limit of the server .

Recommended use **t3.medium** (2) indivual (vCPU, 4 GB memory),
approximately \$ 0.04 per hour , costing only a few cents for several
hours. Terminate immediately after testing .

Settings during creation: Region **us-west-2** , system **Amazon Linux
2023** , create a new security group `For sg-client `, the inbound rules
only need to allow your IP to access port 22 .

## Step 2 : Allow ALB to grant client access

Here\'s a common pitfall. If your ALB is internet-facing, when clients
access it via the ALB \'s public DNS name, even if both are in the same
VPC , traffic will still pass through the public internet exit point,
affecting the ALB. The source address you see is **the client\'s public
IP address** , not its private IP address. IP. Therefore, **in**
`sg-alb` **The source is Configuring rules using the** `"sg-client `**\"
method does not work.**

exist `sg-alb` Add an inbound rule, choosing one of two options:

- **The source is the client\'s public IP address** , and the format
  is\... `xxxx/32 . This is the safest method, but `the public IP
  address will change after the client instance is stopped and restarted
  , requiring modification.
- **Source is** `0.0.0.0/0 `. This is the simplest method; only set it
  this way during testing. After submitting the job, this rule will
  disappear when you delete the ALB .

## Step 3 : Package the client

The client also needs to be packaged into a fat JAR file. If your
client\'s pom.xml file doesn\'t already contain this, then\... For
`the maven-shade-plugin `, just add the following to the consumer \'s
pom.xml file: `mainClass` Change it to the client\'s startup class.

**There is one crucial point to note: it must be retained.**
`ServicesResourceTransformer `**.** Tyrus (WebSocket) The client is
through `META-INF/services` The configuration file is used to find the
specific implementation class. If this file is not correctly merged
during packaging, `ContainerProvider.getWebSocketContainer()` An error
will occur at runtime, indicating that the implementation class cannot
be found, and all WebSocket subscribers will fail to connect.

If your client reads `lift_vertical.json `(for example, used for
reconciliation of rankings), confirm that it is in `src/main/resources`
In this way, they will be packaged together into a JAR file.

After packaging, **first use it on your local machine.** `java -jar`
**Run it** to confirm that it can start normally and that WebSocket can
function correctly. Once the connection is successful, press Ctrl+C to
stop. Do not run the full load test on your local machine, otherwise it
will trigger the school network\'s protection mechanism.

## Step 4 : Upload and install the environment

    # Local 
    scp -i <key> .pem target/client.jar ec2-user@ < client public IP > :~ # 

    EC2 client 
    ssh -i <key> .pem ec2-user@ < client public IP > 
    sudo dnf install -y java-17-amazon-corretto-headless tmux

## Step 5 : Run in tmux

A single load test can take several minutes to run. If run directly
within an SSH session, the client will also stop if the SSH connection
drops (e.g., due to a network fluctuation). tmux This allows the program
to continue running after an SSH connection is lost:

    `tmux new -s test` # Creates a session named `test` 

    # Runs the client in tmux (parameters should be formatted according to your client's actual format) 
    java -Xmx2g -jar client.jar http://skitrak-alb-865987688.us-west-2.elb.amazonaws.com/ski-servlet 

    # Press Ctrl+B, then press D: Exit this session; the client will continue running in the background. 
    # Reconnect: tmux attach -t test

`-Xmx2g` Allocate 2 GB of heap memory to the client . If not specified,
the JVM will\... By default, only physical memory will be used.
One-quarter of the data may not be enough when recording 500,000 request
data entries.

During the load test, you can **open another SSH window** and execute
the command on the client machine. `top `. If CPU utilization remains
close to\... 200% (2) (If all vCPUs are fully utilized), it indicates
that the client has become the bottleneck, which needs to be noted in
the report, or a larger instance should be used instead.

## Step 6 : Retrieve the results back to your local machine

    # Local 
    scp -i <key> .pem -r ec2-user@ < client public IP > :~/results-v2 ./results-v2-ec2

The monitoring script can continue to run on your Mac . It sends only
one request per second, with very low traffic, so it won\'t trigger the
school network\'s protection mechanisms.

## Before you start testing, check these things.

These items are directly related to tonight\'s test results, and it is
recommended that you confirm them one by one before the first official
run:

- [ ] **The HttpClient used by ingest has cookie management disabled** (
  `disableCookieManagement() ). `In your curl response above , ALB\...
  It did return. `AWSALB` Cookies. If the client saves them, all POST
  requests will be sent to the same server . The test results for 1 unit
  and 4 units will be almost the same as those for 1 unit. After
  testing, you can use the message queue in RabbitMQ. `serverId` The
  distribution, or the number of requests for each target in ALB
  monitoring, can be used to confirm that the traffic is indeed being
  distributed across the servers .
- [ ] **Each server​** `/ski-servlet/health` **All returned 200** , and
  were displayed in the target group as healthy. Today\'s 404 issue may
  reappear after each machine restart.
- [ ] **Use a new dayID each time you run it** , or restart before
  running it. To prevent the leaderboard from including cumulative
  values from the previous test, the consumer should avoid this.
- [ ] **Pay attention to the server \'s CPU usage.** After the client is
  moved to EC2 , network latency will be significantly reduced, and the
  server\... The CPU will likely be fully utilized. I previously saw
  that server-1 only had about 24 points left . Once the points are
  exhausted, the CPU\... With only 10% of the baseline performance
  remaining , throughput will suddenly drop midway through the test.
  Monitor this in CloudWatch during the test. `CPUCreditBalance `: If
  you see a sudden drop in throughput, check this metric first.

## The number of threads also needs to be retested.

With both the client and server behind us-west-2 , network latency will
decrease from over 100 milliseconds to just a few milliseconds.
According to Little\'s Law, the required concurrency will be
significantly reduced for the same throughput. The assignment required
testing with 64, 128 , 256 , and 512 threads, which is a good
opportunity to find the new optimal values. It\'s very likely that 128
or 256 threads will already be sufficient to fully utilize the server ;
adding more threads will only increase latency, not throughput. This
data set is also very suitable for inclusion in a report.

## User

How to view the load balancing effect across multiple servers in AWS?

## Claude

There\'s a limitation you need to know first: **ALB\'s** built **-in
CloudWatch metrics don\'t include a metric for \"how many requests
each** server **received .\"** `RequestCount` Statistics can only be
calculated by the entire load balancer, by target group, or by
availability zone; `RequestCountPerTarget` It\'s just an average (total
number of requests divided by the number of targets), and it doesn\'t
show the differences between the servers .

Therefore, to demonstrate the effectiveness of load balancing, several
different perspectives need to be combined. The following sections will
introduce them in order from simple to precise.

## Method 1: Monitoring page for the target group (displays overall status)

EC2 console → **Target Groups** → Select your target group →
**Monitoring** Tab page.

Here you can see:

- **Healthy hosts** : The number of healthy servers . When testing with
  4 servers , it should consistently display 4.
- **Requests** : The total number of requests received by the entire
  target group.
- **Target response time** ：server Response time.
- **HTTP 2XX / 4XX / 5XX** : The number of status codes.

Adjust the timeframe to the testing period and take a screenshot. This
proves that ALB consistently treated these servers as healthy targets
during the test , but **it doesn\'t** prove that requests were evenly
distributed.

## Method 2: Compare the performance of each server CPU (visually demonstrates whether the CPU distribution is even)

CloudWatch console → **Metrics** → **All metrics** → **EC2** →
**Per-Instance Metrics** → Search `CPUUtilization `→ **Select all server
instances** → They will be displayed on the same chart.

If the load balancing is even, **these curves should almost overlap** .
If one curve is significantly higher than the others, it means that the
server is receiving more requests. You can use the same method to check
this. `NetworkIn `(network traffic received by each instance) has a more
direct relationship with the number of requests.

This chart is perfect for inclusion in a report; it shows at a glance
whether the traffic is distributed evenly.

## Method 3: Count the actual number of requests processed by each server (most accurate and simplest)

Tomcat **has access logging enabled by default** , recording one line
for each request processed. Therefore, we only need to count the number
of POST requests in the access logs of each server .

    # Run on your Mac, SSH into each server sequentially to collect statistics 
    for IP address in... < server1 public IP > < server2 public IP > < server3 public IP > < server4 public IP address > ; do
      n = $( ssh -i <key> .pem ec2-user@ $ ip \
          "cat /opt/tomcat9/logs/localhost_access_log.*.txt | grep -c 'POST /ski-servlet/skiers'" )
      echo " $ip : $n " 
    done

A few points to note:

- Access logs **are generated into new files daily** , and all previous
  records are retained. Therefore, it\'s best to clear them before each
  test (
  `` using `sudo rm /opt/tomcat9/logs/localhost_access_log  ``.\*\`,
  then restart the computer). (Tomcat), or only record the test period
  when calculating statistics. Otherwise, the request count from the
  previous tests will also be included.
- The statistics include **retry** requests. Under normal network
  conditions, the number of retries is very small, and the impact is
  negligible.

obtaining the number of requests for each server , we can calculate the
proportion for each server. For example, with 4 servers \... server,
ideally each one is 25%. The report could include a simple table, along
with an indicator of uniformity, such as \"what percentage difference is
there between the most and the least.\"

## Method 4: Utilize the message serverId (no login required) server)

You put it in every message The consumer already knows which server
published each swipe using `the serverId . It only needs to `add a
\`serverId\` to the consumer .
`A ConcurrentHashMap<String, LongAdder> can be used `to count messages
by serverId and periodically printed in the log (or printed when the
server is closed) to obtain the number of messages published by each
server .

The difference between this number and Method 3 is that Method 3 counts
the number of requests **received by** the server , while Method 4
counts the number of requests **successfully published to RabbitMQ.**
The number of messages. The two should be very close.

## Method 5: RabbitMQ Management interface (view in real time)

During the test run, open the RabbitMQ management interface.
**Channels** The page displays the connection to which each channel
belongs (i.e., which server it\'s connected to ) . IP), and the current
The publish **rate of** the channels on several servers should be
roughly the same when combined.

This is suitable for real-time observation during testing, and
screenshots can also be used as supplementary evidence.

## How to combine in the report

The assignment requires providing \"ALB metrics showing request
distribution\" and proof of \"even\" \"distribution\". It is recommended
to organize it as follows:

1.  **Screenshot of the target group monitoring page** : proves that all
    servers were healthy during the test and shows the overall request
    volume.
2.  **the CPU curves of each server superimposed together** :
    intuitively demonstrating that the load is evenly distributed.
3.  **A table showing the number of requests handled by each server**
    (from Method 3 or Method 4): Proving the evenness of the
    distribution with precise numbers.

## If uneven distribution is found

The first thing to check is **whether the client has saved the ALB
cookie . If** cookie management is not disabled , the client will
include it in the cookie data. `AWSALB `cookies send requests, ALB It
will forward all requests to the same server. The server exhibits a
pattern where one server has high CPU usage and receives a large number
of requests, while the others are almost idle. In this case, modifying
the client-side code to disable cookie management and then retesting
will resolve the issue.

## User

How do I design a failover experiment? My idea is to run it first, and
then shut down one of the servers midway through.

## Claude

Your approach is correct: during stable operation, artificially stop one
server and observe how the system detects the fault, how it redirects
traffic to other servers , and its performance after recovery. Several
details need to be carefully designed for the experimental results to be
valuable for analysis.

## First, figure out \"how to shut it down\".

Different shutdown methods will produce different phenomena:

  ----------------------------------------------------------------------------
  Way                  Order                          what happens
  -------------------- ------------------------------ ------------------------
  **Stop Tomcat**      `sudo systemctl stop tomcat`   8080 is immediately
  (Recommended)                                       closed. ALB Reconnecting
                                                      to this server will be
                                                      **immediately**
                                                      rejected. This is
                                                      closest to the message
                                                      \"Application process
                                                      crashed.\"

  Force kill process   `sudo kill -9 <pid>`           ⚠️Your systemd
                                                      configuration is correct
                                                      . `Restart=on-failure `,
                                                      Tomcat It will
                                                      **automatically restart
                                                      in** 5 seconds . This
                                                      test measures
                                                      \"automatic recovery,\"
                                                      not \"failover.\"

  Stop the instance in EC2 Control Panel → Stop       The entire machine
  the console.                                        disappeared. ALB The
                                                      connection requests sent
                                                      received no response and
                                                      could only wait until a
                                                      timeout occurred. The
                                                      failure would manifest
                                                      as slower and more
                                                      erratic behavior.
  ----------------------------------------------------------------------------

The first method is recommended. Note that\... `systemctl stop` Services
that are actively stopped, systemd **It won\'t** restart automatically,
which is exactly the effect we need.

## Second, it\'s important to know how long it takes ALB to detect a fault.

According to the health check parameters required for the task ( 30-
second interval, 3 consecutive failures are considered unhealthy, 2
consecutive successes are considered healthy):

    Fault detection time ≈ 30 seconds × 3 times = maximum of approximately 90 seconds. 
    Recovery detection time ≈ 30 seconds × 2 times = maximum of approximately 60 seconds.

**90 seconds following the failure , ALB The system still considers the
server healthy** and will continue to forward some requests to it. These
requests will fail (because Tomcat has stopped, and ALB\...). Usually
returns (502) The client needs to handle these failures by retrying: the
retried request will most likely be forwarded to another healthy server
and thus succeed.

This \"detection window\" is the most worthy part of the failover
experiment to analyze.

## III. Experimental Design

**Configuration** : Use **Two servers** . Removing one **server** means
losing half of the processing power, which is more noticeable than
removing one server from four (only losing\...). (25%) is significantly
higher.

**The test must be long enough.** After the client moves to EC2 , the
throughput may be very high, 50 Tens of thousands of requests might be
sent in just one or two minutes, which is far from enough to accommodate
a 90- second detection window and a 60- second recovery window.
Therefore, it is recommended **to send 1 million requests** , or reduce
the number of threads, to ensure that the entire test lasts at least 4
to 5 minutes.

**Timeline** :

    T+0s Start the client (and the monitoring script on 
    Mac ). T+60s Execute on server-2 : `date +%T; sudo systemctl stop tomcat` 
    → Record the exact time, as it will be used later to align various data. 
    T+60~150s ALB detection window: Some requests failed, and the client retried. 
    Around T+150s, ALB marks server-2 as... If the system is unhealthy, all traffic will be redirected to server-1. At time 
    T+180, execute the following command on server-2 : `date +%T; sudo systemctl start tomcat`. At time 
    T+180~240, Tomcat will start. After two consecutive successful health checks , server-2... After T+240s was reinstated
      The two servers redistributed the traffic until the test ended.

Both the fault phase and the recovery phase should be included so that
they can be displayed simultaneously. failover and failback (recovery).

During the test run, in the target group **Targets** On the page,
refresh it every now and then to observe the status changes of server-2
: `healthy → draining/unhealthy → initial → healthy `. You can take a
screenshot each time the status changes.

## IV. What do we expect to see, and what data do we need to collect?

  --------------------------------------------------------------
  Data source          Expected phenomena   What does this
                                            prove?
  -------------------- -------------------- --------------------
  **Client throughput  Tomcat was stopped , failover and
  per 10 seconds**     server-2 After being failback on
                       removed, it          throughput
                       stabilized at a low  
                       level, and then rose 
                       again after          
                       recovery.            

  **The client records A batch appeared in  How many requests
  the status code for  the detection window were affected in the
  each request.**      502, then            detection window?
                       disappeared.         

  **The number of      The number of        The retry mechanism
  retries by the       retries increased,   successfully handled
  client, and the      but the final number the failure without
  number of times it   of failures          data loss.
  failed after 5       **approached 0.**    
  retries.**                                

  **WebSocket          Subscribers          WebSocket failover
  subscriber           connected to         also works
  reconnection count** server-2 will        correctly. Because
                       automatically        each server receives
                       reconnect to         all leaderboards
                       server-1 after       through the
                       disconnection.       backplane ,
                                            subscribers will
                                            continue to receive
                                            updates no matter
                                            which server they
                                            reconnect to ; if
                                            you have implemented
                                            cached push, they
                                            will receive the
                                            current leaderboard
                                            immediately after
                                            reconnecting.

  **Monitoring page of Healthy hosts        ALB\'s own
  the target group**   dropped from 2 to 1, perspective
                       then return 2; A     
                       spike appears in ELB 
                       5XX.                 

  **Monitoring scripts The publish rate     The failure only
  for Mac**            decreased and then   occurred at the
                       recovered, but **the server layer in
                       queue depth remained RabbitMQ. Consumers
                       normal.**            are unaffected .

  **Reconciliation of  The expected result  No swipes were lost
  leaderboards after   is MATCH             or double-counted
  the test**                                during the failure.
  --------------------------------------------------------------

Regarding reconciliation, there\'s one situation you need to know in
advance: server-2 At the moment it was stopped, some requests may have
**already been sent. RabbitMQ has not yet returned a response** . The
client will consider these requests to have failed and will retry,
resulting in the same swipe being calculated twice (eventId). (Different
values cannot be identified by the deduplication mechanism). If the
reconciliation result shows \"the actual value is slightly larger than
the expected value,\" this is precisely the reason, and it is also the
best practical example of the \"client-side idempotent key\" improvement
scheme we discussed earlier.

## V. Content that can be analyzed in the report

1.  **Fault detection time** : from when Tomcat stops until ALB marks it
    as faulty. The duration of the unhealthy test was compared to the
    theoretical value (maximum 90 seconds).
2.  **Scope of the failure** : How many errors occurred within the
    detection window, and ultimately how many requests failed (ideally)?
    0).
3.  **Trade-offs in health check parameters** : Shortening the check
    interval and lowering the failure threshold can detect faults
    faster, but at the cost of more frequent health check requests.
    Furthermore, a brief pause could cause a perfectly healthy server to
    be mistakenly identified as unhealthy and removed from service. The
    job requirements of 30 seconds and 3 checks are settings that
    prioritize stability and avoid false positives.
4.  **Throughput variation :** How much did the throughput drop when
    only one server remained, and is it consistent with the results of
    testing with a single server ?

## A small suggestion

**Before starting the test, prepare all terminal windows** : one window
SSHs to\... In server-2, enter the stop command and press Enter when the
time is up; another window will prepare the start command. This ensures
accurate execution according to the timeline and avoids delays due to
temporary logins. Each command is preceded by\... `date +%T` It will
print the exact execution time, which will then be used to align with
the client\'s settings. CSV, CSV of monitoring scripts , and charts from
CloudWatch .

## User

Please provide a report outline for me to review.

## Claude

The outline below is organized entirely according to the assignment
submission requirements, and it indicates what content should be
included in each section and which designs and data we discussed earlier
should be referenced. The report itself is written in English, while the
explanations here are in Chinese.

------------------------------------------------------------------------

# CS6650 Assignment 2 Report

## 0. Repository

- Git repository address
- Directory descriptions (one-sentence summary of each directory):
  `/server-v2 `, `/consumer `, `/deployment `, `/monitoring `,
  `AI_USAGE.md `, `/results-v2`

------------------------------------------------------------------------

## Part I. Architecture Document (no more than 2 pages)

### 1. System Architecture

- **Architecture diagram** : Client → ALB → server-v2 × N → RabbitMQ →
  Consumer → `leaderboard.fanout `→ each server-v2 → WebSocket
  subscriber
- the EC2 instance on the diagram where each component runs , the port
  it uses, and the two protocols (HTTP POST and HTTP POST ). WebSocket)

### 2. Swipe → Leaderboard Sequence Diagram

The sequence diagram shows the following steps: Validation → Query
vertical → Publish message and wait for publisher confirm → Return 201 →
Consumer receives message → Deduplication → Accumulation → Update top-N
→ Merge and push → Publish to fanout → Update cache on each server →
SessionSender pushes to subscribers

### 3. Queue Topology

- `skitrak.exchange `(topic) (type), routing key is
  `` `resort.{id}  ``\` corresponds to 10 \` durable\` queues .
- Queue parameters: TTL, `x-max-length `, used `reject-publish` Strategy
  (and explain why the default is not used) `drop-head `: The latter
  will quietly discard messages.
- Dead letter exchange and dead letter queue
- `leaderboard.fanout `, each server has its own exclusive, auto-delete
  temporary queue.

### 4. Connection-Locality: Backplane vs Co-location

- **Select** : backplane
- **Reason** : The job requires the consumer to run on a separate EC2
  instance; it also needs to be easily scalable to 2 or 4 instances.
  tower At this scale, the cost of message amplification is minimal (as
  estimated earlier can be provided).
- **Cost** : An extra hop of delay and message amplification
- **Methods to control costs** : merge push notifications ( maximum of
  once every 200ms per resort ); an optional improvement is to bind
  topics by resort.
- **When is co-location more appropriate?**
- **An implicit premise** is that each resort has only one publisher,
  which guarantees message order, so version numbers are not needed.

### 5. Consumer Threading Model

- Each resort queue corresponds to one Channels ensure ordering within
  the same resort .
- the number of threads exceeds 10 , the additional threads cannot
  improve the parallelism (explain the contradiction between the
  \"sequence requirement\" and \"testing more threads\" in the
  assignment, and your solution).
- prefetch, batch ack
- Data structures in memory: `skierVertical `, top-N maintenance methods
  (utilizing the property that vertical only increases and never
  decreases), memory limit of the deduplicated set and its selection.
- The state is only stored in memory, consumer Data will be lost upon
  reboot (this is a known limitation; persistence is A3 \'s job).

### 6. Server-side Design

- ChannelPool: One Connection, BlockingQueue Implement lending and
  returning; include a timeout when lending and check channel
  availability when returning.
- The publisher confirms, and why 201 actually means \"accepted\" ( the
  semantics of 202 can be mentioned).
- WebSocket: SessionSender (only one message is sent per session at any
  given time, and only the latest version is retained), pushes cached
  leaderboards when a new subscriber connects, and uses a lock for each
  resort to avoid race conditions.

### 7. Load Balancing Configuration

- Target group, parameters of health examination (30) Second interval /
  5 -second timeout / Healthy threshold 2 / Unhealthy threshold 3)
  Sticky sessions, idle timeout
- **ingest client has disabled cookie management** (and explains why:
  otherwise all POST requests would be routed to the same server ).

### 8. Failure Handling

- Client: Retry 5 times, with exponential backoff
- server: Posting failed and returned a message. 503; Handling RabbitMQ
  connection failure at startup ; Reconnection after connection loss.
- Consumer: Messages that fail to be parsed are directly added to the
  dead-letter queue; messages that fail to publish a leaderboard are
  still ACKed . Swipe (because the leaderboard is derived from the
  state); catch all exceptions in the callback function.
- Idempotency: Deduplication by eventId , and **the deduplication scope
  only covers duplicate deliveries from the broker** , cannot prevent
  duplicates caused by client retries (and the client-side idempotent
  key improvement scheme).
- Automatic restart of systemd and Docker

------------------------------------------------------------------------

## Part II. Test Results

### 9. Test Environment

A table showing the instance type and region used by each component, and
the client running on EC2 (with explanations, see Section 15 ).

### 10. Single Server (500K)

- Screenshot of client output
- Queue monitoring curves: queue depth, publish Rate and ACK rate
- System metrics: server of CPU, memory

### 11. Load Balanced: 2 Servers and 4 Servers (500K, and 1M stress test)

- Client output for each test
- **Evidence of load balancing** : Screenshots of monitoring for the
  target group, overlay graphs of CPU curves for each server , and a
  table showing the number of requests processed by each server .

### 12. Throughput Comparison

  -------------------------------------------------------------------
  Servers    Threads    Throughput   Mean / p99 Peak / avg Consumer
                                     latency    queue      lag p99
                                                depth      
  ---------- ---------- ------------ ---------- ---------- ----------
  1                                                        

  2                                                        

  4                                                        

  4 (1M)                                                   
  -------------------------------------------------------------------

- Comparison of different thread counts (64 / 128 / 256 / 512) under
  each configuration
- **Identify the bottleneck** : Is it the server \'s CPU, CPU timer,
  RabbitMQ, consumer, or the client itself?

### 13. Queue Profile & Consumer Lag

- Determine whether the queue depth curve is a stable plateau or a
  sawtooth shape.
- **The definition** of consumer lag (the merge interval from release to
  aggregation completion, excluding push notifications) and its
  distribution.
- Verify using Little\'s Law : Average queue depth ≈ arrival rate ×
  average lag

### 14. Real Leaderboard Evidence

- Send several messages using a single dayID The screenshot shows the
  curl command and the changes in the leaderboard received by wscat.
- Large-scale reconciliation: each resort displays MATCH (if it appears)
  MISMATCH (Analyze the reasons truthfully)
- Push delay (and explain the clock skew involved)

### 15. Failover Experiment

- Timeline graph: throughput, number of errors, number of healthy hosts
  ; mark the times when Tomcat was stopped and started on the graph.
- Comparison of actual fault detection time and theoretical value
  (maximum approximately 90 seconds)
- How many errors were successfully resolved by retries, and how many
  ultimately failed?
- WebSocket subscriber reconnection
- Selection of health examination parameters

### 16. Performance Analysis & Lessons Learned

- Performance improvements across different configurations, and the
  reasons why they do not show a linear increase.
- **Lessons learned from the network environment** : The first time I
  ran it on my local machine, there was a pause of approximately 120
  seconds. You can detect this by monitoring blank data and ALB\... By
  analyzing the target response time and server metrics, **the server-
  side issue was ruled out** , and the problem was traced to the local
  network (the campus network\'s protection mechanism). Therefore, the
  client was migrated to EC2 . Furthermore, using Little\'s Law , it was
  determined that the majority of the 140ms latency during local runtime
  originated from the public network, indicating that the bottleneck was
  actually the network, not the system itself.
- Other findings, such as Tomcat starting before RabbitMQ causing WAR
  deployment failure, and your solutions.

------------------------------------------------------------------------

## Part III. AI Usage Reflection ( `AI_USAGE.md `, 5 point)

- **Where was AI used** (be specific): ChannelPool, topology, consumer
  Data structures, backplane, deployment, monitoring scripts,
  troubleshooting
- **At least two suggestions should be adopted : for example,**
  implementing it using BlockingQueue. ChannelPool, Select Backplane
  Plan, SessionSender Design
- **At least two rejected or revised suggestions** ---this best
  demonstrates your understanding. From our conversation, we have
  several real-life examples:
  - **Version number issue** : AI You suggested using an
    auto-incrementing serial number for the consumer as the leaderboard
    version number. However, **you pointed out that the serial number
    resets to zero after a consumer restart, causing all subsequent new
    leaderboards to be discarded . Ultimately,** the solution was
    changed to a single lock for each resort .
  - **ChannelPool​** `isOpen()` **Check** : AI The initial suggestion
    conflicted with the RabbitMQ client\'s auto-recovery feature.
  - **Misjudgment of the cause of the freeze** : AI Initially, we
    suspected a server -side issue, but based on the monitoring data and
    **ALB metrics you provided , we later determined it was a local
    network problem.**
  - **The average value of the monitoring script is diluted** : you find
    that the average depth includes idle time before and after the test.
- **The areas where you make your own reasoning and decisions** : for
  example, how to define the boundaries of consumer lag , the reasoning
  process for still acking the swipe when the leaderboard fails to
  publish , or other decisions you make after independent thinking.
- **Conversation history** : Please include a sharing link or export it
  to\... `transcripts/` Table of contents

**This section must include your own genuine experiences and
judgments.** The examples I listed above are just those that happened
during our conversation. You need to choose examples **that you have
genuinely thought through and can clearly explain** . The assignment
also explicitly states that the grading is based on honesty and insight,
not the amount of AI used , and you may also be asked to explain any
part of it on the spot.

------------------------------------------------------------------------

## Priorities when time is tight

The deadline is tomorrow. If time is limited, we suggest allocating
tasks according to the weighting of the scores:

1.  **Required to complete** : Architecture document ( Sections 1 to 8
    ), 1 Test results and comparisons for 1 unit /2 units /4 units (
    Sections 10-12 ), evidence of load distribution, queue screenshots,
    leaderboard evidence, `AI_USAGE.md`
2.  **Valuable :** The analysis of consumer lag and Little\'s Law , and
    the troubleshooting process for network problems in Section 16.
3.  **I\'ll do it later** : failover Experiment, 1M Stress testing and a
    complete comparison of different thread counts

## User

    | Run | Servers | Runtime (s) | Throughput (req/s) | p50 Latency (ms) | Failed / Retries | Peak / Avg Queue Depth | Consume Rate (/s) | Consumer Lag p95 (ms 
    ) 
    | 135.11 | 3701 | 95.89 | 0 / 0 | 61 / 18.2 | 3472 | 185 | 
    | 2 | 2 | 96.40 | 5187 | 24.67 | 0 / 0 | 45 / 26.9 | 4716 | 140 | 
    | 3 | 4 | 83.01 | 6024 | 37.22 | 0 / 0 | 98 / 59.7 | 5556 | 210 | 
    | 4 | 4 (1M) | 165.60 | 6039 | 47.13 | 0 / 0 | 124 / 66.8 | 5717 | 217 |

Check where the formatting is wrong; it can\'t read the table format.

## Claude

**there is an extra column** in the second row (the separator row) .

The header has 9 columns:

    | Run | Servers | Runtime (s) | ... | Consumer Lag p95 (ms) |

But there\'s an extra paragraph at the end of the line break.
`-------------------------------------------| `, which has been changed
to 10 columns. Markdown **The number of columns separating rows must be
exactly the same as the table header** ; otherwise, the entire content
will not be recognized as a table and will only be displayed as ordinary
text.

Also, you use it outside ```` ``` ```` It\'s wrapped in a code block.
The content inside the code block will **be displayed as is** and will
not be rendered as a table. If you also write it this way in your README
or report, you need to remove these symbols.

The revised version is as follows:

    | Run | Servers | Runtime (s) | Throughput (req/s) | p50 Latency (ms) | Failed / Retries | Peak / Avg Queue Depth | Consume Rate (/s) | Consumer Lag p95 (ms) |
    |---|---|---:|---:|---:|---|---|---:|---:|
    | 1 | 1 | 135.11 | 3701 | 95.89 | 0 / 0 | 61 / 18.2 | 3472 | 185 |
    | 2 | 2 | 96.40 | 5187 | 24.67 | 0 / 0 | 45 / 26.9 | 4716 | 140 |
    | 3 | 4 | 83.01 | 6024 | 37.22 | 0 / 0 | 98 / 59.7 | 5556 | 210 |
    | 4 | 4 (1M) | 165.60 | 6039 | 47.13 | 0 / 0 | 124 / 66.8 | 5717 | 217 |

The rendered result:

  ------------------------------------------------------------------------------------------
  Run   Servers     Runtime(s)   Throughput       p50 Failed /  Peak /    Consume   Consumer
                                    (req/s)   Latency Retries   Avg     Rate (/s)    Lag p95
                                                 (ms)           Queue                   (ms)
                                                                Depth             
  ----- --------- ------------ ------------ --------- --------- ------- --------- ----------
  1     1               135.11         3701     95.89 0 / 0     61 /         3472        185
                                                                18.2              

  2     2                96.40         5187     24.67 0 / 0     45 /         4716        140
                                                                26.9              

  3     4                83.01         6024     37.22 0 / 0     98 /         5556        210
                                                                59.7              

  4     4 (1M)          165.60         6039     47.13 0 / 0     124 /        5717        217
                                                                66.8              
  ------------------------------------------------------------------------------------------

I also changed the number column to right alignment (written in the row
separator as\...). `--- `:), this will align the numbers bit by bit,
making comparison easier. This step is not necessary; use `---` There
are absolutely no problems.

## There\'s another piece of data I need you to confirm.

**Run 1 \'s p50 latency is 95.89 ms, exactly the same median as the
previous run on Mac (the one with the 120 -second pause)** , accurate to
two decimal places. It\'s highly unlikely that two different tests would
produce the exact same value; I suggest you check if it was copied from
an older output.

I roughly checked the other values, and the throughput and runtime match
up. For example, 500,000 ÷ 135.11 ≈ 3701 , 1,000,000 ÷ 165.60 ≈ 6039.

Additionally, the table lacks **a column for the number of threads** .
If the number of threads used in these tests differs, it\'s best to add
this column; otherwise, readers won\'t be able to determine whether the
throughput difference stems from the number of servers or the number of
threads. For example, Run 3 (4 tower The p50 latency of the server is
actually lower than that of Run 2 (2 tower The higher number of servers,
coupled with the presence of the thread count column, makes this
phenomenon easier to explain.

## User

Please generate a simple framework for the readme files of serverv2,
clientv2, and consumer.

## Claude

all three READMEs are complete, each consisting of forty to fifty lines,
and they are placed in separate folders. `readme-v2` Below `server-v2 `,
`consumer `, `client-v2` Simply copy the files from the three folders to
the corresponding directories in the repository. As before, this is
written in English, and the structure of each file is as follows:

- **server-v2** : three Endpoint, responsibilities of each class,
  message fields, environment variables, packaging and deployment,
  Tomcat Explanation of the 404 error caused by starting RabbitMQ before
  it , and test commands.
- **Consumer** : Processing flow, thread model, data structures in
  memory, delivery guarantee, failure handling, known limitations (state
  will be lost after restart), and methods for running with systemd .
- **client-v2 :** Modifications compared to A1 (connection) ALB,
  disabling cookie management, recording the final leaderboard), the
  method for running on EC2 , the running parameters, and the output
  file.

You need to check the actual code to confirm or replace the following:

- **all** `<...>` **These are all placeholders** , including the class
  name of ChannelPool and consumer. The number of threads and prefetch,
  interval of merged push, top-N The size (I wrote it according to the
  top 20 you mentioned earlier ), and the command-line arguments for the
  client.
- **The class name** is based on your previous\... `git status` The
  filenames that appear in the file are `RabbitMQManager `,
  `SessionSender `, `ConsumerApp `, and `Broadcaster .` (etc.),
  `SkierServlet` and `HealthServlet` This is based on the naming
  convention of A1 ; if it differs, it needs to be modified.
- **client-v2** , delete the **reconciliation section if you ultimately
  fail to achieve reconciliation, to avoid** discrepancies between the
  README description and the code.
- What is written in server-v2 is `/health` return 200. If you have
  implemented RabbitMQ as we discussed\... Return before connection is
  successful \"503\", remember to add that as well.

## User

Make it a little simpler; yours is a bit complicated.

## Claude

All three READMEs have been streamlined, with each containing only about
20 lines, and only the three sections---\"What is this?\", \"How to
configure it?\", and \"How to run it\"---have been retained.

- **server-v2** : A brief introduction, three key points endpoint,
  environment variables, and packaging/deployment commands.
- **Consumer** : A brief introduction, three key design elements,
  environment variables, and execution commands.
- **client-v2** : A brief introduction, three differences from A1 , and
  the running command.

Detailed design specifications (ChannelPool, SessionSender,
deduplication, merged push, etc.) can be included in the architecture
document of the report. Before use, \[the document should be
prepared/implemented\]. `<key> `, `<server-IP>` Replace these
placeholders with your own values, and then double-check that the WAR
name and jar name match the actual packaged file.

## User

The quality of your output isn\'t good enough; let\'s work on them one
by one. The root directory readme: This sends the data to several Ingest
servers behind ALB. Send each card swipe to In RabbitMQ, a consumer
reads the total elevation gain for each person, and each ski resort
maintains a leaderboard of the top 20. The leaderboard is then sent back
to each ski resort via RabbitMQ \'s fanout exchange. server, server Push
it to your own WebSocket subscribers. All are on AWS EC2 us-west-2. The
new directory added in assignment2 includes\... server-v2, consumer,
client-v2, deployment, monitoring, results-v2, and more AI_USAGE.md;
assignment1 The server, client-part1, client-part2, and results
directories are still there and haven\'t been touched. List the
directories for both assignments , explaining the function of each in a
single sentence, with each directory having its own explanation. README,
all Java 11 and Maven.

## Claude

root directory `README.md` I\'ve written it according to your structure:
The first paragraph explains the data flow of the entire system, then
two tables list the directories of Assignment 2 and Assignment 1
respectively , with a sentence explaining the purpose of each directory,
and the last sentence explaining that each directory has its own\... The
README file, along with the technology stack used.

I slightly adjusted the wording of the last sentence. You meant \"all
Java 11 and\...\" \"Maven\", but `monitoring/` The monitoring scripts
are written in Python , not\... Therefore, I wrote it as: all Java code
uses Java 11 and\... Maven, `monitoring/` The queue monitoring script is
a Python 3 script that only uses the standard library. If your\...
`monitoring/` If you\'ve changed the implementation or prefer to keep
the original wording, let me know and I\'ll change it back.

Additionally, `deployment/` It contains shell scripts and configuration
files, and it\'s not Java code, so the current syntax is accurate for
it.

## User

Write a simple README for server-v2; you can use a table. It\'s based on
Tomcat. 9\'s web The app, packaged as ski-servlet.war, receives card
swipes, sends them to RabbitMQ, and then pushes the leaderboard to its
WebSocket subscribers. It has three interfaces: POST\... The URL
\`/ski-servlet/skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}\`
will return a 201 status code only if RabbitMQ confirms the request; a
400 status code will return if the parameters are incorrect; and a 503
status code will return if the request fails. (GET request)
\`/ski-servlet/health\` returns a 200 status code when connected to
RabbitMQ, and a 503 status code when disconnected; WebSocket
\`/ski-servlet/leaderboard/{resortID}\` pushes the top 20 rankings for
this ski resort. The class \`SkierServlet\` handles validation and
publishing; \`RabbitMQManager\` creates connections, queues, and a
channel pool; \`ChannelPool\` has 32 channels; \`LiftVerticals\` reads
\`lift_vertical.json\`; \`SwipeInfo\` stores queued messages;
\`LeaderboardEndpoint\` and \`SessionSender\` handle push notifications;
and \`HealthChecker\` performs health checks. Environment variables
include \`RABBIT_HOST\`, \`RABBIT_USER\`, \`RABBIT_PASS\`, and
\`SERVER_ID\`. The build process is \`mvn clean package\`, and the WAR
file is copied to Tomcat\'s webapps directory.

## Claude

server- v2 `README.md` I\'ve written it according to your instructions,
including a one-sentence introduction, three tables for interfaces,
classes, and environment variables, and finally, the build and
deployment commands. The content is exactly as you described, without
any extra additions.

There\'s one deployment-related point I\'d like you to check:
`SERVER_ID` It\'s set via environment variables. If it\'s written in\...
`setenv.sh` The other servers were replicated using AMI , so **all
servers will use the same\...** `SERVER_ID `. Thus, use the SERVER_ID
from the message. `serverId` When counting requests to each server , it
becomes impossible to distinguish between them. You could check if the
value is different on each server , or if this variable isn\'t set in
the code, use the hostname as the default value.

## User

Write a simple README for the consumer. A standalone Java program reads
card swipes from resort.1 to resort.10, calculates each person\'s total
vertical drop and the top 20 skiers each day for each resort, and sends
the updated leaderboard to leaderboard.fanout. Key points: Each of the
10 channels is responsible for one queue, so processing should be
sequential; acks should only be made after processing is complete, and
prefetching is used. 50. Bad messages are added to the dead-letter
queue; duplicates are removed by eventId; when rankings are the same,
the smaller skierID ranks first; the leaderboard is merged and sent
every 200 milliseconds; the number of processes, broadcasts, and
consumption latency are printed every 5 seconds. Classes: ConsumerApp,
SwipeInfo, Aggregator, TopN, Broadcaster, LagStats, each with a short
message. Environment variables RABBIT_HOST, RABBIT_USER, RABBIT_PASS,
CONSUMER_THREADS (default 10), PREFETCH (default 50),
BROADCAST_INTERVAL_MS (default 200), HEALTH_PORT (default 8081). \`mvn
clean package\` generates consumer.jar, Java\... Run with -jar, health
checks are performed on port 8081, and on EC2, systemd is used.

## Claude

consumer​ `README.md` It\'s written: a one-sentence introduction, six key
points, two tables for classes and environment variables, and finally,
the commands for building and running.

There are two places you need to check against the code:

1.  **The descriptions for each class are based on my inferences from
    the class names and the functions you described.** in particular
    `ConsumerApp` and `LagStats` The division of responsibilities: Which
    class is responsible for printing the statistical output every 5
    seconds, and in which class is the health check service started? If
    it differs from the code, please tell me the actual division of
    responsibilities, and I will modify it accordingly.

2.  **10​ \"channel, each responsible for one queue\" and \"**
    `CONSUMER_THREADS "` **The phrase \"configurable\" being grouped
    together might raise questions for readers** : If we put\...
    `CONSUMER_THREADS` Set to 20 or 5. What happens? If your code
    reallocates queues to threads when the number of threads is not
    equal to 10 (for example, if it\'s set to 5 , each thread is
    responsible for 2 queues), you can add a note explaining this in the
    key points. This also corresponds to the assignment requirement to
    test different numbers of consumer threads.

## User

Write the README for client-v2. Modify the following from client-part2:
Address, number of entries, and number of threads are read from
environment variables: SKITRAK_HOST defaults to localhost:8080,
TOTAL_SWIPES defaults to 500000, and THREADS defaults to 1024. Cookies
are disabled to prevent ALB from storing all card swipes on a single
server. After sending, wait 3 seconds for new leaderboards before
disabling subscribers. The results, when written to results-v2, now
include skierID and liftID in the CSV file, along with the final
leaderboard for each ski resort. Package it into client.jar. During
testing, this jar is placed on the EC2 server of us-west-2 and started
from my computer using monitoring/run_remote.sh.

## Claude

client- v2 `README.md` It\'s written: a one-sentence introduction, four
modifications compared to client-part2 , the environment variable table,
the build and run commands, and the output file table.

There are three points I added based on previous information, which I
need you to confirm:

1.  **The names of the output files** ( `allswipes.csv `,
    `throughput_over_time.csv `, `final_leaderboards.txt `) are from the
    output printed by the client during your first run. If you change
    the names later, you need to update them accordingly.
2.  **The column order in the CSV file is as follows:** I appended the
    columns after the five columns specified in A1. `skierID` and
    `liftID `. If the actual order is different, the code shall prevail.
3.  `SKITRAK_HOST` **Format** : The default value is
    `localhost:8080 `appears to only contain the host and port, so\...
    `/ski-servlet` This path should be manually appended by the client
    in the code. In the example, I wrote\... `SKITRAK_HOST=<ALB-DNS> `,
    if your code requires a path, or requires\... `http:// `, remember
    to modify the example.

Additionally, `THREADS` The default value is 1024, but the test results
table in your report doesn\'t specify the number of threads used. If all
threads were used in the actual test\... 1024. You can add this column
to the table in the report so that readers can know that the number of
threads was the same in these tests.

## User

The monitoring README should contain two small sections, each with its
usage. \`queue_monitor.py\` calls the RabbitMQ management interface
every second, recording the total depth, publish rate, ACK rate, and
dead letter count of 10 card-swiping queues, writing it to a CSV file.
When finished (Ctrl+C), it prints the peak and average values. It uses
only the Python standard library. The usage is to set \`RABBIT_PASS\`
and then run \`python monitoring/queue_monitor.py \--host RabbitMQ
public IP \--out file path\`. \`run_remote.sh\` runs a load test on the
client EC2 via SSH, displaying the output locally. After completion, the
results are copied back to the corresponding folder in \`results-v2\`.
The usage is to set the variables \`CLIENT\` and \`ALB\` and then run
\`./monitoring/run_remote.sh round name\` . Thread count Total number of
entries.

## Claude

monitoring​ `README.md` It\'s all written down. There\'s a short
description for each of the two tools, followed by usage instructions.

There is one thing I need you to confirm: `CLIENT` How should I fill in
this variable? I\'ve used a placeholder. `<client-instance> `. If your
script requires this format\... `ec2-user@<IP> `, not just the IP
address. It\'s best to change the placeholder to\...
`ec2-user@<client-IP> `, so that others can fill it in correctly without
making mistakes.

Additionally, if you want the usage to be more intuitive, you can\...
`run_remote.sh` Add a line of actual examples below the command, such as
`./monitoring/run_remote.sh run3-4servers 1024 500000 `.

## User

Write the README for results-v2 to explain how to interpret the data.
results.md contains all the results tables. run1 to run3 represent 1, 2,
and 4 servers respectively, run4 represents 4 servers with 1 million
records; threads64, threads128, and threads256 represent the number of
different client threads; consumer5 and consumer15 represent the number
of different consumer threads; failover indicates when one server is
stopped midway. Unless otherwise specified, all records have 512 client
threads, 10 consumer threads, and 500,000 records. Each folder contains
client_output.txt (client output), allswipes.csv (each card swipe),
throughput_over_time.csv (throughput every 10 seconds),
final_leaderboards.txt (final leaderboard), and queue.csv (queue
monitoring data). The screenshots folder contains client output for each
round, RabbitMQ curves for each round, ALB allocation, CPU usage,
leaderboard evidence, failover data, and several configuration diagrams;
just list them out.

## Claude

results- v2 `README.md` It\'s written: First, let me explain.
`results.md` It is a summary table, then another table lists the test
configurations corresponding to each folder and explains the default
configurations, another table describes the files in each folder, and
finally lists the types of screenshots in the screenshots folder .

There is one inconsistency to note: client-v2 The README states that
`THREADS` **The default value is 1024** , but this code specifies **a
default of 512 client threads** . If your tests always pass\...
`run_remote.sh` Explicitly passing in the number of threads is not
contradictory to either approach, but it might confuse the reader.
Consider choosing one of two options: either change the default value in
the client code to\... 512, keep it consistent with the test
configuration; or add a note after the default configuration line in
this README stating \"the number of threads is determined by\...\"
`run_remote.sh` \"Input\".

## User

Write only the deployment README; do not generate the service files and
scripts that already exist. All servers are Amazon Linux 2023,
us-west-2, and the services connect to it using RabbitMQ\'s private IP
address. Machines: RabbitMQ runs on Docker; the consumer and four
servers run on systemd, all on micro servers; the client is t3.medium;
and there is an ALB. File placement instructions: Run rabbitmq-setup.sh
once on the RabbitMQ machine; place consumer.env.example in
/etc/skitrak-consumer.env; place skitrak-consumer.service and
tomcat.service in /etc/systemd/system; place server-setenv.sh.example in
/opt/tomcat9/bin/setenv.sh; alb.md contains the ALB configuration;
security-groups.md contains security group rules. Deployment order:
RabbitMQ, consumer, server-1, servers-2 through 4 are copied using the
AMI from server-1, then the ALB is created, and finally the clients. The
restart order should be written as follows: start RabbitMQ first, and
only start the others after it is running. If the server is started
before RabbitMQ, the deployment will fail and Tomcat will need to be
restarted.

## Claude

deployment​ `README.md` It\'s finished. Only this one file was generated;
the existing service files and scripts in the directory were not
modified. The content includes: a machine table, a file location table,
deployment order, and restart order. In addition, two lines of systemd
enable commands were added under the file table , and a method to check
whether RabbitMQ is ready was added to the restart order .

There are two things I need you to confirm:

1.  **The instance type I wrote is** `micro `, it doesn\'t specify
    whether it\'s t2.micro or\... t3.micro. It\'s recommended to change
    this to the accurate model number, as the two have different CPU
    integration mechanisms, and your report may discuss the impact of
    CPU integration on performance.
2.  **Enable command** `<service-name> `corresponds to the service name
    on the consumer machine. `skitrak-consumer `, the corresponding
    server is `Tomcat `. If you want to make it easier for readers to
    follow the instructions, you can break this line down into two
    specific commands.

## User

According to the assignment requirements, should the architecture
diagram be placed in a separate repository, or can I submit the PDF
directly in the assignment submission window?

## Claude

According to the assignment requirements, the architecture diagram **can
be placed in the PDF submitted to Canvas ; it does not need to be put
into a separate repository.**

The assignment submission requirements are organized as follows:

> **Submit a PDF to Canvas containing:** 1. **Git Repository URL** ---
> new folders:
> `/server-v2`、`/consumer`、`/deployment`、`/monitoring`、`AI_USAGE.md`
> 2. **Architecture Document (2 pages max)** --- System architecture
> diagram、Swipe → leaderboard sequence diagram、Queue topology...... 3.
> **Test Results** ......

As can be seen, the architecture diagram and sequence diagram belong to
item 2 , \"Architecture\". \"Document\", and this document is **Part of
the PDF** . The requirements for the repository only list those few
directories and\... `AI_USAGE.md `does not require an architecture
diagram.

However, two points are worth noting:

1.  `AI_USAGE.md` **It\'s the only file that requires special attention
    to where it\'s placed.** The homework says you can put it in The
    repository can be included, **or** appended to the architecture
    document. Since you\'ve already planned to put it in the repository
    , that meets the requirement. If you also want to show it in the PDF
    , you can mention its location in the repository in the PDF .
    Additionally, it\'s recommended to include the conversation log
    (sharing link, or exported to the repository ) as part of the
    assignment . `transcripts/` (Table of Contents), which can provide
    evidence for your reflection.

2.  **Adding a copy of the architecture diagram to the repository is an
    optional bonus.** For example, put the image in Create
    `a file named docs/architecture.png `and then reference it in the
    README file in the root directory . This way, when others browse
    your repository , they can understand the entire system structure
    without opening the PDF . This is not an assignment requirement; you
    can skip it if you are short on time.

## User

I\'ve almost forgotten what we discussed. Based on the AI requirements
of the assignment, could you list out what we discussed?

## User

Summarize from Assignment 2 onwards

## Claude

In accordance with the requirements for reflecting on the use of AI ,
the discussions starting from Assignment 2 are organized into two parts:
the first part is the discussion content listed by topic, and the second
part is the material that can be directly used for reflection.

First, I need to clarify one point: I can only see the content of the
conversation. Which suggestions you ultimately incorporated into your
code can only be determined based on information you later sent me (such
as the README content, runtime output, and `git status `). Where there
is no evidence, I will mark it as \"unable to confirm,\" and please base
your reflections on what you actually did.

------------------------------------------------------------------------

## I. Discussion topics listed by theme

### 1. Understand the assignment and overall structure

- I summarized the assignment into four parts and pointed out several
  points that need attention: the contradiction between the order
  requirement and the number of consumer threads, the frequency of
  leaderboard push, the memory of the deduplication set, the timing of
  returning 201 , and the cost.
- **You restated your understanding of the architecture** , and I made
  corrections: ALB It\'s an AWS -managed service; you only need to
  configure it, no code is required. It calculates incrementally for
  each swipe, rather than calculating uniformly for all swipes ;
  backplane The Chinese should be \"backboard\"; and most importantly:
  **each server must have its own dedicated fanout queue** . If a queue
  is shared, each message will only be received by one of the servers .
- **Comparison between Backplane and Co-location** : I recommend
  choosing\... The reason for choosing backplane is that the job
  requires the consumer to run on a separate EC2 instance and needs to
  be easily expandable to 2 or 4 instances . The server, and at this
  scale, the cost of message amplification is small.

### 2. RabbitMQ Basics and Topology

- Add to `amqp-client` Dependencies (cannot use provided scope), in
  `ServletContextListener` Create connection, guest Users can only log
  in from their own machine.
- Queues are \"declarative,\" and declaration operations are idempotent;
  exchanges, queues, and bindings all require declaration, and omitting
  a binding will result in messages being silently discarded.
- TTL and queue length limits: `drop-head` It will silently discard
  messages, so it is recommended to use\... `reject-publish `.
- dead letter exchanges and dead letter queues; differences between
  queue parameters and policy configuration methods; `x-` Misspelled
  parameter names at the beginning **will not cause an error** ; they
  will simply be ignored.
- All operations must be performed through a channel , and a temporary
  channel is used when setting up the topology.
- Verify the topology using the management interface: Manually publish a
  message to test the route, and use Get messages to test the dead
  letter queue.

### 3. Server -side deployment

- **You asked if ChannelPool can be used with an array and polling** : I
  explained that polling can only determine \"who gets a turn\", but
  cannot know \"who is currently available\". Adding a lock will result
  in the problem of still having to wait even when an idle channel
  exists. Therefore, I suggest using BlockingQueue to implement lending
  and returning.
- When lending out, there is an overdue period. `finally` Before
  returning the item, check if the channel is available.
- Publisher confirms( `confirmSelect` The meaning of 201, and a
  comparison of the three release schemes; the semantic meaning of 201
  is actually \"accepted\" (which is worth mentioning). 202).
- 201 and 503 ; why use 503 instead of 500.
- load `lift_vertical.json `(stored using an array, read via classpath ,
  and validated at startup); **you asked if each ski resort shares the
  same lift** , and based on the original text of the assignment, I
  infer that they do, and suggest you refer to the actual structure of
  the file.
- generate UUID; **eventId is generated by the server and can only
  prevent duplicate deliveries by the broker , but cannot prevent
  duplicates caused by client retries** , or the improved solution of
  client idempotent keys.
- The assignment requires you to \"implement your own reconnection\" and
  discuss situations where the RabbitMQ client\'s automatic recovery
  function is not covered. I suggest you check with your teacher to see
  if you can use the library\'s built-in functionality.

### 4. Consumer

- **You described your understanding of consumer** , and I corrected two
  points: consumer It\'s a subscription queue where messages are pushed
  by the broker , rather than \"retrieving all\" messages. swipe; ack It
  should be done after the processing is complete.
- Thread model: Each sort queue corresponds to one thread. Channels are
  used to ensure order; parallelism cannot be increased beyond 10
  threads; prefetch Batch ACKs are the key factors affecting throughput.
- **You designed a two-layer Map data structure** (
  `skifield:day:skierID).` and `Ski Resort: Sky `→ Leaderboard), I
  confirmed this design and discussed the trade-offs between using a Map
  or an ordered List internally for the leaderboard , as well as
  utilizing \"vertical\" The property of \"only increasing and never
  decreasing\" simplifies the maintenance of top-N .
- Rules for tiebreakers (only those with a score greater than the
  leaderboard are included; in case of a tie, the score is sorted by
  skierID ); merged push notifications; and use snapshots to avoid
  thread safety issues.
- Handling parsing failures: classic The queue does not count retries,
  so you need to distinguish between permanent and temporary errors
  yourself; poison messages; **ack the swipe even if publishing the
  leaderboard fails** (because the leaderboard is a derived state);
  exceptions in the callback function will cause the channel to be
  closed.
- The timing of deduplication (completing aggregation first, then
  marking as processed); the memory limit of the deduplicated set.

### 5. WebSocket push

- A separate channel is used to receive fanout messages, using a
  temporary queue named by the server, autoAck; the resortID is passed
  using the routing key .
- **concurrent sending within the same session will cause errors** :
  Yes, it will, even if there\'s only one thread, it might trigger
  (because sending is asynchronous). I recommend configuring one for
  each session . SessionSender sends only one message at a time and
  retains only the latest version.
- **You\'re debating whether to push a cached leaderboard when new
  subscribers connect** : I suggest pushing it, then point out a race
  condition, and propose using version numbers to solve it.
- **The version number issue (see Part 2)** : In the end, it was changed
  to a scheme where each resort has its own lock.

### 6. Verification and Measurement

- **the swipe sent \"** : Manual smoke testing using a unique dayID ,
  plus automatic reconciliation after the load test; and several
  prerequisites for reconciliation (using a new dayID each time).
  (dayID, waiting for the leaderboard to stabilize, sorting rules must
  be consistent with consumer ).
- **You asked how to define the boundary of consumer lag** : I suggest
  defining it as the merge interval from release to aggregation
  completion, excluding push notifications; and explain its difference
  from \"queue depth less than\...\" The goal of "1000" can be linked to
  Little's Law .
- **You asked about the cost of counting each entry** : I introduced the
  bucketed histogram. **You then asked if bucketing would result in a
  loss of precision and how much memory would be needed to store the
  data using a List** : I calculated the memory overhead and explained
  that since the input itself is an integer millisecond, buckets that
  are 1 millisecond wide would have almost no loss of precision.

### 7. Deployment

- Each component is deployed on a separate EC2 instance ; the difference
  between public and private IP addresses ; security groups are created
  based on roles; and configurations are moved from the code to
  environment variables.
- Run with Docker RabbitMQ (later supplemented with fixed hostnames and
  mounted data volumes); consumer of pom.xml(shade Plugins, SLF4J
  version, `release` (Parameters); run the consumer with systemd .
- Automatic startup on boot: RabbitMQ Two layers of protection are
  needed; consumer need `StartLimitIntervalSec=0 `; Tomcat The systemd
  configuration.
- **Starting Tomcat before RabbitMQ will cause WAR deployment to fail**
  : Tomcat The process won\'t exit, so systemd can\'t help. I suggest
  using a background thread to continuously retry the connection and
  having health return 503 before the connection succeeds .
- The concept of AMI ( **you asked what the relationship is between the
  machine copied using AMI and the original machine** ), creating an
  image, and copying. server, scripts for updating WAR files.
- ALB configuration; ALB Troubleshooting a 503 error (target group is
  empty, or the instance is not in an enabled availability zone); ALB
  Troubleshooting 404 errors (Tomcat) (Starts before RabbitMQ ); Apache
  HttpClient saves by default. Cookies cause all requests to be routed
  to the same server .
- ALB (approximately \$ 47 for two months ).

### 8. Monitoring script

- I wrote it according to your requirements. `queue_monitor.py `(using
  only the standard library) has been tested with a simulated interface.
- **You find that the average value is diluted by the amount of free
  time before and after the test (see Part 2)** .

### 9. Troubleshooting during the first load test

- 512 threads, with a throughput of approximately 110 seconds in the
  middle. 0. My initial guess was a server -side issue (JVM).
  Insufficient memory, ChannelPool Exhausted, RabbitMQ (Connection
  blocked).
- **You suggested that the problem might be with your Mac\'s connection
  to ALB** , and provided monitoring data. The CSV file revealed that
  the monitoring script also went offline for 127 seconds, a time that
  coincides with the start of the main phase when approximately 480
  connections were established simultaneously. You also provided the
  server \'s \... The CPU, memory, and ALB Target Response Time (maximum
  only 120 milliseconds) were checked, and it was ultimately confirmed
  that the server was always normal, and the problem was on the local
  network (the campus network\'s protection mechanism).
- I explained why the client appeared to be a DoS attack from the
  network device\'s perspective, and you decided to migrate the client
  to run on EC2 .
- Incidentally, I discovered a problem: Mac The clock skew between EC2
  and EC2 (the median push delay is\...) (-119ms); 37 requests
  ultimately failed, which may affect reconciliation.

### 10. Load balancing, failover and report

- Several methods to check load balancing (ALB) The metrics do not
  include the number of requests for each target; this is demonstrated
  using a CPU curve overlay and Tomcat access logs.
- **You proposed the idea of a failover experiment (stopping one machine
  midway through). Regarding the server** , I added the difference in
  shutdown methods ( `kill -9).` (will be automatically restarted by
  systemd ) ALB The time required to detect the fault (up to
  approximately 90 seconds) and the data that needs to be collected.
- Report outline; fixed the table format (an extra column in the
  separator row), and **I also pointed out that the p50 of Run 1 had the
  exact same result as the previous run on Mac , so it was probably
  copied incorrectly** .
- README: **You felt my initial output wasn\'t high enough quality** ,
  and subsequently provided specific content requirements. When writing
  the README for the root directory , I pointed out `that monitoring/`
  The script inside is Python, not Java.

------------------------------------------------------------------------

## II. Materials that can be used for reflection on the use of AI

### You discovered an error in the AI , or rejected its suggestions .

These are the most valuable parts of the reflection.

1.  **The version number will be reset to zero after the consumer
    restarts.** I suggested using an auto-incrementing consumer sequence
    number as the leaderboard version number. **You pointed out that
    after a consumer restart, the sequence number would start from 0 ,
    causing all subsequent new leaderboards to be discarded as old
    versions** . You also proposed using a serverTimestamp instead.
    After analyzing the risks of timestamps, I ultimately suggested
    using a lock for each resort to structurally eliminate this race
    condition. (I cannot confirm which solution you ultimately adopted.)
2.  **The reason for the pause in the first load test.** I suspected it
    was a server -side issue, **but you suggested it might be a local
    network problem and provided data that ultimately proved your
    judgment correct.** Similarly, going back a time, you said \"the
    management interface cannot be accessed when the client is
    running,\" which I attributed to RabbitMQ resource exhaustion at the
    time, and now it seems to be the same network problem.
3.  **The average value of the monitoring scripts has been diluted.** My
    script included idle time before and after the test when calculating
    the average depth. **You were the one who discovered this problem**
    , and I later changed it to only count active windows.
4.  **The accuracy issue of bucketed histograms.** You questioned
    whether bucketing would result in a loss of precision and asked me
    to calculate the memory overhead of storing data using a List . This
    shows that you are testing the AI \'s solution, rather than
    accepting it outright.
5.  **the README .** You felt the quality of the content I generated
    wasn\'t good enough, so we changed it to giving you specific
    requirements one by one, and you took the lead in creating the
    content.

### AI correcting its own errors

6.  **ChannelPool​** `isOpen()` **The check and automatic recovery
    functions conflict.** My initial suggestion was to discard and
    create a new channel if it was found to be closed when returning it.
    Later, during discussions about the auto-recovery feature, I
    discovered that this approach would empty the entire pool during a
    connection loss, so I revised it to differentiate between \"the
    connection was lost\" and \"only the channel is broken\".

### The suggestion you adopted (which can be confirmed based on the information you provided later)

7.  **SessionSender** ( `git status)` It appeared
    `SessionSender.java `): Only one message is sent per session at any
    given time, and only the latest version is retained.
8.  **Backplane solution and merged push (** described in your consumer
    README) `leaderboard.fanout ( `merges and publishes every 200
    milliseconds).
9.  **cookie management on the client (** this is stated in your
    client-v2 README ).
10. **health returns 503 when RabbitMQ disconnects (** this is stated in
    your server-v2 README ).
11. **Migrate the client to run on EC2** , and deploy using Docker and
    systemd .

### Your own judgment, or the parts where you didn\'t directly copy AI.

12. **SERVER_ID is set using environment variables.** I suggested using
    the hostname, but you chose an environment variable (I reminded you
    that machines copied via AMI will use the same value; I\'m not sure
    how you ultimately handled that).
13. **failover experiment was proposed by you** ; I only added some
    details.
14. **consumer lag .** You were the one who raised the question of \"how
    should the boundaries be defined\"; you need to explain your
    reasoning regarding which definition to ultimately adopt.
15. **The consumer \'s data structure was designed by you first** ; I
    simply confirmed it and discussed the trade-offs involved.

### This can be written as a problem that requires \"reasoning to reach a conclusion\".

I raised these questions during the discussion, but you need to be able
to explain the final conclusion yourself:

- vertical operations satisfies the commutative law, so why is it still
  required to process the tasks in order?
- ACK this post when publishing the rankings fails? swipe?
- Deduplication can only cover duplicate deliveries from the broker ,
  not duplicates caused by client retries.
- The assignment requires testing 10 to 80 consumer threads, but there
  are only 10 queues. How did you handle this contradiction? (You
  tested\... ) `consumer5` and `consumer15 `)?

------------------------------------------------------------------------

## Suggestions for writing reflections

- **Choose examples from Part Two that you have actually experienced and
  that you can explain clearly.** The assignment clearly states that you
  may be asked to explain any part of it.
- **The most convincing argument is that \"you discovered an error in
  the AI \".** Points 1, 2, and 3 all contain specific dialogues that
  can serve as evidence.
- **The conversation log is attached.** Assignments are suggested to
  include a shareable link, or export the conversation to the repository
  . `transcripts/` Table of Contents. This way, for every example you
  write in your reflections, you can find the corresponding original
  text in the dialogue.

## User

Can you write a template first and let me take a look? It requires all
periods to be marked with 1. 2.3.4. The format should not be complicated
and should correspond to the AI of the assignment. usage requirements

## Claude

`AI_USAGE.md` The template is complete, written in English, with all
bullet points numbered using 1, 2, 3, etc., without using complex
formatting. Each section corresponds one-to-one with the assignment
requirements:

1.  **Where I Used AI** : This corresponds to the assignment
    requirements of \"where and for what purpose, be specific\".
2.  **Suggestions I Accepted** : Corresponding to \"at least two
    suggestions accepted\", I wrote four, and explained the reasons for
    each.
3.  **Suggestions I Rejected or Corrected** : This corresponds to \"at
    least two rejected or corrected suggestions\".
4.  **What the AI Got Wrong, and What I Reasoned Through Myself** : This
    corresponds to \"a point where the AI made a mistake, or where you
    made a decision based on your own reasoning.\"
5.  **Reflection** : A summary in one or two sentences.
6.  **Transcripts** : Dialogue logs attached to the corresponding
    assignment suggestions.

Use \[the appropriate method\] for all fields that require you to fill
in or confirm. `<...>` It was marked:

1.  **The opening paragraph** should clearly state which code you wrote
    and which files were generated by AI . This must be stated
    truthfully; the assignment has emphasized that you need to
    understand and be able to explain every line of code you submit.
2.  **Part 3 , Item 1** : Add how you ultimately resolved the version
    number issue.
3.  **Part 3 , Item 3** : SERVER_ID That one should only be kept if you
    genuinely intend to choose the environment variable and can provide
    a reason; otherwise, delete it.
4.  **Part 4 , Item 2** : Write down a design decision you made,
    choosing the one from the examples in parentheses that you can best
    explain.
5.  **Parts 5 and 6** : Your own summary, and links to the conversation
    log **.**

Additionally, each line in Parts 2, 3, and 4 is based on actual events
from our conversation, but written in the first person. Before
submitting, please check each line to ensure it matches your actual
experience and the code. If any discrepancies are found, modify or
delete them.

## User

How do I export our conversation?

## Claude

There are two official methods, suitable for different purposes.

## Method 1: Generate a shareable link (best suited for this assignment)

The assignment suggests including a shareable link. The steps are: click
the \"Share\" button in the upper right corner of the dialog box, then
click \"Share\" again in the pop-up window to generate a shareable link.

Several points to know:

1.  **It is a snapshot.** What you\'re sharing is a snapshot of the
    conversation at the moment you click share. Messages sent afterward
    will remain private unless you update the snapshot. Therefore, it\'s
    best to generate the link **only after our discussion is completely
    finished** , or update the snapshot before submitting, so that the
    link includes the complete conversation.
2.  **Who can see this?** Public links are available on Free, Pro , and
    Max plans. Team Conversations can only be shared within the
    organization with Enterprise members. If you\'re using a school or
    team account, public links may not work. In this case, you can use
    an invitation system: invite specific people to view the
    conversation via email. This feature is available in all plans.
    Invited individuals can view a snapshot of the conversation but
    cannot reply, copy it to their own account, or continue the
    conversation. You can invite teaching assistants or teachers by
    email.
3.  **The uploaded file will not be included.** If the shared
    conversation contains attachments, the attachments themselves will
    not be included in the snapshot and will remain private. People with
    the link can only see the conversation content and Claude \'s reply.
4.  **Can be cancelled at any time.** You can turn off sharing at any
    time in the \"Share\" menu, or in Settings \> Privacy \> Shared
    chats . Once turned off, the link will become invalid.

For detailed instructions, please refer to the official help article:
[Share and unshare
chats](https://support.claude.com/en/articles/10593882-share-and-unshare-chats)

## Method 2: Export account data (suitable for storing) `transcripts/` Table of contents)

If you want to save the conversation as a file and put it in the
repository \... `transcripts/` The directory contains the account data
export function, located under Settings, Privacy, and Export data . This
function is not available in the iOS or Android apps. After the export
is complete, you will receive an email with a download link. You must
log in to your account to download the data via this link, which expires
within 24 hours of being sent . If it expires, you can perform the
export again.

It\'s important to note that this method exports **all data from your
account** , in the following format: JSON is not a standalone
conversation. You need to find this conversation within it and then
reorganize it into a readable format, which is relatively more
complicated.

## Suggestions for this assignment

**Just use the share link** ; fill it in. `AI_USAGE.md` The Transcripts
section is sufficient.

Before generating a public link, it\'s recommended to review the
conversation to ensure there\'s no information you don\'t want to
disclose. For example, this conversation might contain your ALB \'s DNS
name. Fortunately, it will be deleted after you submit your assignment.
ALB will invalidate this address. Also, the RabbitMQ password mentioned
in the conversation was represented by placeholders; the actual password
was not revealed.
