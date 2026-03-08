# EC2 Cloning & Multi-Node Scaling Guide

To perform the 2-node and 4-node tests required for Assignment 2, follow these steps to clone your `server-v2` instance.

## Method A: Create an AMI (Recommended for Consistency)
This creates a "snapshot" of your entire server (OS + Java + Code), allowing you to launch perfect copies.

1.  **Stop the current Server**: Go to EC2 Console -> Instances -> Select `server-v2`.
2.  **Create Image**: 
    - Right-click Instance -> **Image and templates** -> **Create image**.
    - Image name: `server-v2-template`.
    - Click **Create image**. Wait a few minutes (check the "AMIs" tab in the left sidebar).
3.  **Launch from AMI**:
    - Once the status is "Available", select the AMI -> **Launch instance from AMI**.
    - Number of instances: **3** (to get a total of 4).
    - Ensure you use the **same Security Group** and **Key Pair**.
4.  **Startup**:
    - SSH into each new instance.
    - Run the startup command:
      ```bash
      java -jar server-v2.jar --spring.rabbitmq.host=<MQ_PRIVATE_IP>
      ```

## Method B: Manual "Launch More Like This"
If you don't want to wait for an AMI:
1.  EC2 Console -> Instances -> Select `server-v2`.
2.  **Actions** -> **Image and templates** -> **Launch more like this**.
3.  *Note*: This only copies the hardware/network settings. You will need to manually `git pull` and rebuild the code on each machine unless you use Method A.

---

## Final Step: Add to ALB
1.  EC2 Console -> **Target Groups** -> Select `chat-server-tg`.
2.  **Targets** tab -> **Register targets**.
3.  Select all 3 new instances -> **Include as pending below**.
4.  **Register pending targets**.

Wait for the "Health Status" to become **Healthy** (Healthy) for all instances before running the test.
