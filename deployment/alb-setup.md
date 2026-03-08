# ALB （WebSocket + Sticky Session）

## Architecture

```
Client → ALB (port 80/443) → Target Group → [server-v2 EC2 × N台]
                                              ↓
                                          RabbitMQ EC2
                                              ↓
                                          Consumer EC2
```

---

## Step 1: Confirm EC2 Instances Are Ready

Before setting up ALB, ensure your server-v2 instances are running and healthy.

**Run on each server-v2 EC2 instance:**
```bash
# start server-v2
java -Xmx1g -Dspring.rabbitmq.host=<RabbitMQ_EC2_私有IP> -jar target/chat-server-0.0.1-SNAPSHOT.jar

# verify health check endpoint is accessible
curl http://localhost:8080/health
# should return: {"status":"UP"}
```

**Record each server-v2 private IP address** (EC2 Console → Instances → Private IPv4 Address).

---

## Step 2: Create Target Group

> AWS Console → EC2 → Target Groups → Create target group

| Field | Value |
|---|---|
| Target type | **Instances** |
| Target group name | `chat-server-tg` |
| Protocol | **HTTP**（ALB does SSL termination, backend uses HTTP） |
| Port | `8080` |
| VPC | Select the VPC where your EC2 instances are located |
| Protocol version | **HTTP1** |

**Health checks configuration:**

| Field | Value |
|---|---|
| Health check protocol | HTTP |
| Health check path | `/health` |
| Healthy threshold | 2 |
| Unhealthy threshold | 3 |
| Timeout | 5 seconds |
| Interval | 30 seconds |
| Success codes | 200 |

Click **Next** → Select your server-v2 EC2 instances → **Include as pending below** → **Create target group**

---

## Step 3: Create Application Load Balancer

> EC2 → Load Balancers → Create Load Balancer → **Application Load Balancer**

| Field | Value |
|---|---|
| Name | `chat-alb` |
| Scheme | **Internet-facing**（clients from public network） |
| IP address type | IPv4 |
| VPC | Same as EC2 |
| Availability Zones | At least 2 AZs, each AZ with one subnet |

**Listeners：**

| Protocol | Port |
|---|---|
| HTTP | 80 |

> If you have an HTTPS certificate, you can add 443, but for testing, use HTTP 80.

**Default action：** Forward to → `chat-server-tg`

Click **Create load balancer** and wait for the status to change to **Active** (about 1-2 minutes).

---

## Step 4: Configure Sticky Session (WebSocket must!)

WebSocket connections must be kept on the same server, so sticky session must be enabled.

> EC2 → Target Groups → `chat-server-tg` → **Attributes** tab → Edit

| Field | Value |
|---|---|
| **Stickiness** | ✅ Enable |
| Stickiness type | **Load balancer generated cookie** |
| Stickiness duration | `86400` seconds (1 day, ensure WS connections do not switch) |

Click **Save changes**.

---

## Step 5: Configure WebSocket Idle Timeout

WebSocket long connections will be disconnected by ALB's default 60 seconds idle timeout.

> EC2 → Load Balancers → `chat-alb` → **Attributes** → Edit

| Field | Value |
|---|---|
| **Idle timeout** | `4000` seconds (far greater than WebSocket keep-alive interval) |

Click **Save changes**.

---

## Step 6: Configure Security Groups

**ALB Security Group** needs to allow client access:
```
Inbound:  TCP 80  from 0.0.0.0/0   (clients HTTP)
Outbound: ALL     to   <EC2-SG>    (forward to backend)
```

**EC2 Security Group** needs to allow ALB forwarding:
```
Inbound:  TCP 8080  from <ALB-SG>  (only accept ALB traffic)
Inbound:  TCP 22    from <your IP>  (SSH management)
```

> Note: For EC2 security group, fill ALB's Security Group ID (not IP) for inbound 8080 source, so EC2 won't be directly accessible.

---

## Step 7: Verify ALB

```bash
# 1. Get ALB's DNS address
# Console: EC2 → Load Balancers → chat-alb → DNS name
# Example: chat-alb-123456789.us-east-1.elb.amazonaws.com

# 2. Test health check
curl http://<ALB_DNS>/health

# 3. Test WebSocket connection
wscat -c ws://<ALB_DNS>/chat/1
```

**Target Group health status verification:**
> EC2 → Target Groups → `chat-server-tg` → **Targets** tab
> All instances should be **healthy** ✅

---

## Step 8: Add Multiple Instances (2, 4 for testing)

When you need horizontal scaling:

> EC2 → Target Groups → `chat-server-tg` → **Targets** → **Register targets**
> Select new server-v2 instances → Include as pending → **Register pending targets**

Each new instance startup command is the same, replace RabbitMQ IP:
```bash
java -Xmx1g -Dspring.rabbitmq.host=<RabbitMQ_EC2_私有IP> -jar target/chat-server-0.0.1-SNAPSHOT.jar
```

---

## Step 9: Test Sticky Session Behavior

```bash
# Send request, observe Set-Cookie: AWSALB=...
curl -c cookies.txt http://<ALB_DNS>/health -v

# With cookie, should route to same EC2
curl -b cookies.txt http://<ALB_DNS>/health

# View ALB access logs to confirm distribution
# Console: EC2 → Load Balancers → chat-alb → Attributes → Access logs → Enable
```

---

## Step 10: Configuration Summary
The detailed configurations for the Application Load Balancer, Target Groups, and Session Stickiness are documented above. Ensure these match your AWS environment settings for successful deployment.

---

## Common Issues

| Problem | Reason | Solution |
|---|---|---|
| WebSocket connection 101 immediately drops | Idle timeout too short | Step 5: Change to 4000s |
| Each request goes to different server | Sticky session not enabled | Step 4: Enable AWSALB cookie |
| Target unhealthy | /health endpoint not accessible | Check EC2 SG allows ALB access to 8080 |
| 502 Bad Gateway | EC2 not started or port wrong | `curl http://<EC2 private IP>:8080/health` local test |
