# Kubernetes — **pure in-cluster data plane** (default)

Postgres, RabbitMQ, and Redis run **inside the cluster** (`postgres.yaml`, `rabbitmq.yaml`, `redis.yaml`).  
`server-v2` and `consumer-v3` use **`postgres-service`**, **`rabbitmq-service`**, **`redis-service`** from `config.yaml`.

**You do not need** Terraform `create_ec2_rabbitmq`, `create_ec2_postgres`, `use_amazon_mq`, or `use_rds_postgres` for this layout. Terraform only provisions **VPC + EKS** (and related networking).

---

## 1. Build images (names must match manifests)

From the **repository root**:

```bash
docker build -t cs6650-server-v2:latest -f server-v2/Dockerfile server-v2
docker build -t cs6650-consumer-v3:latest -f consumer-v3/Dockerfile consumer-v3
```

## 2. ECR push (EKS)

```bash
export AWS_REGION=us-east-1
export ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
aws ecr get-login-password --region $AWS_REGION | docker login --username AWS --password-stdin $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com

aws ecr create-repository --repository-name cs6650-server-v2 --region $AWS_REGION 2>/dev/null || true
aws ecr create-repository --repository-name cs6650-consumer-v3 --region $AWS_REGION 2>/dev/null || true

docker tag cs6650-server-v2:latest $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/cs6650-server-v2:latest
docker tag cs6650-consumer-v3:latest $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/cs6650-consumer-v3:latest
docker push $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/cs6650-server-v2:latest
docker push $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/cs6650-consumer-v3:latest
```

## 3. Point Kustomize at ECR

```bash
cd k8s
kustomize edit set image cs6650-server-v2=$ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/cs6650-server-v2:latest
kustomize edit set image cs6650-consumer-v3=$ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/cs6650-consumer-v3:latest
```

(`kustomization.yaml` is updated in place; commit or revert as you prefer.)

## 4. Install Ingress NGINX (if not already)

Example (Helm; chart/version may vary):

```bash
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm install ingress-nginx ingress-nginx/ingress-nginx -n ingress-nginx --create-namespace
```

Use the same ingress class your `ingress.yaml` expects (`kubernetes.io/ingress.class: nginx`).

## 5. Apply stack

```bash
kubectl apply -k .
kubectl get pods -w
```

Edit **`secret.yaml`** if passwords differ from dev defaults.

## 6. Ingress / WebSocket

`ingress.yaml` uses a rule **without** `host` so the **ALB/NLB DNS name** works without `/etc/hosts`.  
For local testing only, you can add `host: chat.local` and map it in hosts.

## 7. Local cluster (minikube / kind / Docker Desktop)

- Load images into the cluster (`kind load docker-image …` / minikube image load), **or**  
- Keep short names and run `kubectl apply -k .` after `kustomize edit set image` back to local names if your tool requires it.

`imagePullPolicy` is **Always** so nodes always reconcile with the registry tag.

## 8. PVC

If `postgres` stays **Pending**, set a valid **`storageClassName`** on `postgres-pvc` for your cluster (EKS default is often `gp2` or `gp3`).

---

## Production hardening (already in `server.yaml`)

- **`SERVER_METRICS_ALLOW_HTTP_MV_REFRESH=false`** — blocks abusive `GET /metrics?refreshMaterializedViews=true`.
- **`SERVER_INSTANCE_ID`** from **`metadata.name`** for targeted broadcast routing.
