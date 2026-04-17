# JMeter — alignment with this project (server-v2)

Course docs often use placeholder paths (`/api/messages`, `/chat` without room). **This stack uses:**

| Kind | URL | Notes |
|------|-----|--------|
| **WebSocket** | `ws://<host>:8080/chat/{roomId}` | `roomId` = **1–20** (see `ChatWebSocketHandler`). Example: `ws://localhost:8080/chat/5` |
| **HTTP** | `http://<host>:8080/health` | JSON health — good for **load / baseline** without WS plugin |
| **HTTP** | `http://<host>:8080/metrics` | Heavy; avoid `refreshMaterializedViews=true` through ALB (504 risk). Use for light checks or after load ends |

**ALB:** use `http://<alb-dns>/health` (port **80** → target 8080). WebSocket from JMeter: `ws://<alb-dns>/chat/1` (no TLS in typical class setup; use `wss` only if you terminate TLS on ALB).

---

## Install (summary)

- **JMeter 5.6+**, **JDK 8+** (JDK 17+ matches this project).
- Extract e.g. `C:\jmeter` or `/opt/jmeter`.
- **GUI:** `bin\jmeter.bat` (Windows) or `bin/jmeter`
- **CLI:**  
  `bin/jmeter -n -t plan.jmx -l results.jtl -e -o report_html`

**WebSocket plugin (if you load-test WS in JMeter):** Plugins Manager → *WebSocket Samplers by Peter Doornbosch* (or manual JAR into `lib/ext`). See your course handout.

---

## Running tests (course checklist)

**GUI mode (editing / debugging only):**

- Linux / macOS: `./bin/jmeter`
- Windows: `bin\jmeter.bat` (or run from `bin` as `.\jmeter.bat`)

**Non-GUI mode (real load runs):**

```bash
./bin/jmeter -n -t your-test-plan.jmx -l results.jtl -e -o report-folder
```

**Generate HTML report from an existing JTL** (no re-run):

```bash
./bin/jmeter -g results.jtl -o html-report-folder
```

On Windows, use `.\jmeter.bat` instead of `./bin/jmeter`, and use quoted paths if directories contain spaces. Example **baseline / stress** commands for this repo are in **Course test plans** later in this file.

---

## Test scenarios (rubric) → this repo

Use your assignment’s scenarios; map them to the files below and **state the mapping in your PDF**.

| Scenario | Rubric (typical) | In this repo |
|----------|------------------|--------------|
| **Baseline** | ~**1000** concurrent users; **~100K** API calls; **~5 min**; **70% reads / 30% writes**; report response time, throughput, error rate | `assignment-baseline-100k-5min.jmx`: **700+300** threads (**70/30** split), **100K** HTTP samples total, **60 s** ramp. **Note:** duration is driven by **loops** reaching 100K samples, not a fixed **300 s** wall-clock timer — if the grader requires a strict 5-minute **duration**, add a **Scheduler** duration and tune **throughput** in a copy of the plan (see baseline subsection below). |
| **Stress** | **500** concurrent users; **200K–500K** calls over **30 min**; mixed read/write; observe breaking point and **resource utilization** | `assignment-stress-30min.jmx`: **350+150** threads, **30 min**, throughput caps targeting **~400K** total HTTP samples if the SUT keeps up — **edit Constant Throughput Timer** values in the `.jmx` if you need closer to **200K** or **500K**. |

**Operations (read/write):** rubric “writes” are approximated as **`GET /metrics`** (heavy); “reads” as **`GET /health`**. Literal chat traffic is **WebSocket** — add the WebSocket plan from [WebSocket Sampler](#websocket-sampler-course-handout--this-project) if you must show wire-format chat in JMeter.

---

## HTTP-only test plan (no plugin) — quick baseline

1. **Test Plan** → **Thread Group**  
   - Threads, ramp-up, duration or loop count per your assignment.
2. **HTTP Request Defaults**  
   - Server: `localhost` or ALB DNS  
   - Port: `8080` (direct to server) or `80` (via ALB HTTP)
3. **HTTP Request** sampler  
   - Path: `/health`  
   - Method: GET  
4. **Listeners:** Summary Report, Aggregate Report; optional **Save Response to file** off for load runs.
5. **Assertions:** Response code 200; body contains `"UP"` or similar if you want stricter checks.

Use this for **before/after optimization** comparisons (throughput, p95/p99 as reported by JMeter for **HTTP**). This measures **ingress + health stack**, not full chat persistence latency.

---

## WebSocket Sampler (course handout + this project)

Install **WebSocket Samplers by Peter Doornbosch** (Plugins Manager → *Available Plugins* → search *WebSocket* → install → restart JMeter). After restart, samplers appear under **Sampler** (names may be *WebSocket Open Connection*, *WebSocket Single Write*, etc., depending on version—not always the literal words “WebSocket Sampler”).

### Handout “WebSocket Test Plan Example” — same shape, **server-v2** settings

Course tree (conceptually):

```text
Thread Group (100 users)
├── WebSocket Open Connection
├── Loop Controller (10 iterations)
│   └── WebSocket Single Write
└── WebSocket Close Connection
```

**Adapted for this repo** (room in path, valid JSON, JOIN before TEXT):

**100 users = 100 unique `userId` values (no CSV required):** use **`${__threadNum}`** (starts at 1 per thread) plus a fixed offset so each thread gets a **stable, distinct** numeric id. Use the **same** expression for JOIN and every TEXT in that thread so the virtual user does not “change identity” mid-run.

- **`userId`** (digits only, range **1–100000**): `"${__intSum(${__threadNum},10000,)}"` → thread 1 → `10001`, thread 100 → `10100`.  
- **`username`** (3–64 chars, letters and digits only): `"user${__intSum(${__threadNum},10000,)}"` → `user10001` … `user10100`.

If you ever run more than **90,000** threads with this offset, lower the offset or switch to CSV so `userId` stays ≤ 100000.

```text
Thread Group (100 users, ramp e.g. 60s)
├── WebSocket Open Connection
│   └── Server: localhost, Port: 8080, Path: /chat/1   ← use /chat/1 … /chat/20, NOT /chat alone
│   └── Protocol: ws, timeouts per handout (increase read timeout if TEXT fails under load)
├── WebSocket Single Write — JOIN (one frame before the loop)
│   └── Message:
│       {"messageId":"${__UUID}","userId":"${__intSum(${__threadNum},10000,)}",
│        "username":"user${__intSum(${__threadNum},10000,)}","message":"Hello",
│        "timestamp":"${__time(yyyy-MM-dd'T'HH:mm:ss'Z')}","messageType":"JOIN"}
├── Loop Controller (10 iterations)
│   └── WebSocket Single Write
│       └── Use existing connection: ✓
│       └── Message:
│           {"messageId":"${__UUID}","userId":"${__intSum(${__threadNum},10000,)}",
│            "username":"user${__intSum(${__threadNum},10000,)}",
│            "message":"Test message ${__counter(FALSE,)}",
│            "timestamp":"${__time(yyyy-MM-dd'T'HH:mm:ss'Z')}","messageType":"TEXT"}
└── WebSocket Close Connection
```

**Alternative:** **CSV Data Set Config** with columns `userId,username` (e.g. 100 rows) and reference `"${userId}"` / `"${username}"` in the JSON when you need ids to match a real account table.

The handout’s minimal line  
`{"userId": "${userId}", "message": "Test message ${__counter()}"}` **is not enough** for `server-v2` — you still need **`username`**, **`timestamp`**, **`messageType`**, and a **numeric** `userId` (see table below).

**Troubleshooting (handout + this app)**

| Issue | What to check |
|--------|----------------|
| Connection refused | `server-v2` on **8080**, Docker/Rabbit/Postgres up; `check-stack.ps1`. |
| Plugin not found | JAR in `lib/ext`, **restart JMeter**; sampler names vary by version. |
| Timeout | Raise connection/read timeouts; `/metrics` unrelated — WS is separate. |
| Message format | Match `ChatWebSocketHandler`: path room **1–20**, full JSON, **JOIN** (recommended) then **TEXT**. |

### 1. What to change vs the generic handout

| Handout example | For **server-v2** you must use |
|-----------------|--------------------------------|
| Path: **`/chat`** | **`/chat/{roomId}`** with **`roomId` = 1–20** (integer), e.g. **`/chat/1`**, **`/chat/5`**. Plain **`/chat`** is wrong for this codebase. |
| Example JSON with **`"roomId": "room1"`** | Room is taken from the **URL path**, not from a `room1`-style id. JSON still needs valid fields below; you can omit `roomId` in the body or use **`"5"`**—the server validates **path** room. |
| **`"userId": "user123"`** | **`userId`** must be **digits only**, range **1–100000** (e.g. `"12345"`). |
| **`"username": "user123"`** | **3–64** chars, **letters and digits only** (e.g. `"user12345"`). |

### 2. Suggested GUI layout (one thread = one chat user)

1. **Thread Group** — set users, ramp-up, loops as needed. **Each thread = one distinct chat user** — use **`userId` / `username` expressions from the section above** (or CSV) so 100 threads ⇒ 100 different ids.  
2. **WebSocket Open Connection** (or your plugin’s open sampler):  
   - **Server:** `localhost` (host only, no `http://`).  
   - **Port:** `8080`.  
   - **Path:** `/chat/1` (or `/chat/${__Random(1,20)}` if the sampler supports expressions).  
   - **Protocol:** `ws` (use `wss` only if TLS terminates in front).  
   - **Connection timeout:** `20000` ms (or as handout).  
   - **Response / read timeout:** start with **≥ 10000 ms** for TEXT under load (`6000` may be tight).  
3. **WebSocket Single Write** — **first** frame (do **not** use “existing connection” on the very first write if your plugin ties that to an already-open socket):  
   - **Request data (JOIN):**

```text
{"messageId":"${__UUID}","userId":"${__intSum(${__threadNum},10000,)}","username":"user${__intSum(${__threadNum},10000,)}","message":"Hello","timestamp":"${__time(yyyy-MM-dd'T'HH:mm:ss'Z')}","messageType":"JOIN"}
```

4. **Loop Controller** (e.g. 10 iterations) → **WebSocket Single Write** for **TEXT**:  
   - Check **Use existing connection** (per handout) so frames reuse the same socket.  
   - **Request data:**

```text
{"messageId":"${__UUID}","userId":"${__intSum(${__threadNum},10000,)}","username":"user${__intSum(${__threadNum},10000,)}","message":"Test ${__counter(FALSE,)}","timestamp":"${__time(yyyy-MM-dd'T'HH:mm:ss'Z')}","messageType":"TEXT"}
```

   - Each TEXT should use a **new `messageId`** (`${__UUID}`) for idempotency, matching `client_part2`.

5. Optional **WebSocket Single Write** — **LEAVE** with same `userId` / `username` / `timestamp` rules, `messageType":"LEAVE"`.  
6. **WebSocket Close** (if your plugin provides it) — clean shutdown.

**Field rules (server `ChatWebSocketHandler`):** `message` non-empty; `timestamp` like ISO `yyyy-MM-ddTHH:mm:ss` with **`T`** and colons (length ≥ 19); `messageType` one of **`JOIN`**, **`TEXT`**, **`LEAVE`**.

### 3. Why this is not the handout’s one-liner JSON

Handout sample `{"userId":"user123", "message":"Hello", "roomId":"room1"}` **fails** this server: `userId` must be numeric, `username` is required, `timestamp` and `messageType` are required, and room must be **`/chat/1`…`/chat/20`**, not `room1`.

### 4. Practical note (submission)

Many teams still use **HTTP** plans (`assignment-baseline-100k-5min.jmx`) for rubric throughput tables and add a **smaller** WebSocket plan (screenshots) to show literal **JOIN/TEXT** traffic. Document both if TA asks.

---

## Course test plans (Assignment 3 PDF / rubric)

**Approach A (implemented in this repo):** use **two Thread Groups** and set **thread counts** so they match roughly **70% / 30%** of request volume (baseline: 700+300; stress: 350+150). Inside each group, keep the usual **HTTP Request Defaults → HTTP Request → assertions**; you do **not** need a single group with Throughput Timer or If Controller + random split.  
Note: `health-baseline.jmx` has only **one** Thread Group — it is a small smoke test, not the full baseline scenario from the rubric.

Handouts often cite **`/api/messages`** and **`/chat`** without a room id. **This repo’s JMeter plans use real paths** and map **70% read / 30% write** as follows (say this explicitly in your PDF):

| Handout | In these `.jmx` files |
|--------|------------------------|
| **70% reads** | **`GET /health`** (light HTTP) |
| **30% writes** | **`GET /metrics`** without `refreshMaterializedViews` (heavy read/analytics; stresses DB + cache). *True* chat writes are WebSocket — add the **WebSocket plugin** + `client_part2`-style JSON if the grader insists on wire-format “writes”. |

### Baseline scenario (~1000 users, ~100K samples, 5 min)

- **File:** `assignment-baseline-100k-5min.jmx` — **700** threads × **100** loops on `/health` + **300** × **100** on `/metrics` = **100,000** HTTP samples; ramp **60 s** (override with `-Jramp_sec=`).
- **Rubric “5 minutes”:** this plan matches **100K samples** and **~1000 threads** quickly; wall-clock time may be **shorter or longer** than 300 s depending on SUT speed. For a **strict 300 s** run, duplicate the plan in the GUI, set the Thread Group **Duration** to **300** seconds, switch loops to **forever** (or high), and use **Constant Throughput Timer** (or similar) so **70/30** traffic sums to ~**100K** requests over 5 minutes — verify sample counts in the HTML report.
- **Before running:** start Docker stack, **server-v2** (`:8080`), **consumer-v3** (`:8081`). Run `database/check-stack.ps1` if unsure.

**Non-GUI (Windows, adjust `C:\jmeter` to your install):**

```powershell
cd C:\jmeter\bin
.\jmeter.bat -n -t "D:\6650\assignment 3\load-tests\jmeter\assignment-baseline-100k-5min.jmx" `
  -l "D:\6650\assignment 3\load-tests\jmeter\results\baseline-100k.jtl" `
  -e -o "D:\6650\assignment 3\load-tests\jmeter\results\baseline-100k-report"
```

Optional overrides: `-Jhost=localhost -Jport=8080 -Jread_threads=700 -Jwrite_threads=300 -Jread_loops=100 -Jwrite_loops=100 -Jramp_sec=60`

### Stress scenario (500 users, 30 min, capped ~400K samples total)

- **File:** `assignment-stress-30min.jmx` — **350 + 150** threads, **30 min** duration, infinite loop + **Constant Throughput Timer** per group (~**9333** + **4000** samples/min → ~**400K** total if the SUT keeps up).
- Tune machine / JVM heap if JMeter itself becomes the bottleneck.

```powershell
cd C:\jmeter\bin
.\jmeter.bat -n -t "D:\6650\assignment 3\load-tests\jmeter\assignment-stress-30min.jmx" `
  -l "D:\6650\assignment 3\load-tests\jmeter\results\stress-30m.jtl" `
  -e -o "D:\6650\assignment 3\load-tests\jmeter\results\stress-30m-report"
```

Optional: `-Jduration_sec=1800` (default 1800). To change rate caps, edit the **Constant Throughput Timer** values in the `.jmx` (GUI) — they must be numeric literals.

---

## Non-GUI — small smoke test (`health-baseline.jmx`)

```powershell
cd C:\jmeter\bin
.\jmeter.bat -n -t "D:\6650\assignment 3\load-tests\jmeter\health-baseline.jmx" -l "D:\6650\assignment 3\load-tests\jmeter\results\smoke.jtl" -e -o "D:\6650\assignment 3\load-tests\jmeter\results\smoke-report"
```

Use `-Jthreads= -Jramp_sec= -Jloops=` to scale the smoke run.

---

## Required metrics (from your assignment)

For **each** scenario (baseline and stress), collect:

| Metric | Where to get it |
|--------|------------------|
| **Average** response time | JMeter HTML report / **Statistics** / **Aggregate Report** |
| **p95** & **p99** response times | JMeter HTML report (**Percentiles** tables / graphs) |
| **Throughput** (requests / second) | JMeter report (**Throughput**); align with rubric wording |
| **Error rate** (%) | JMeter report (**Error %**); investigate non-200 rows in the JTL if needed |
| **Resource utilization** — CPU, memory, **DB connections** | **Not** produced by JMeter alone — use **EC2 / RDS / container** dashboards, `htop`, Windows Task Manager, `pg_stat_activity`, or `monitoring/postgres-snapshot.sql` during/after runs |

**Regenerate report from JTL without re-running:**

```bash
./bin/jmeter -g results.jtl -o html-report-folder
```

---

## Scenario mapping (course “70% read / 30% write”)

This project has **no** separate REST “read message” API for chat history in the load path. Approximate mapping:

| Course idea | This project |
|-------------|----------------|
| “Read” | Mostly **`GET /health`** and optional **`GET /metrics`** (light) |
| “Write” | **WebSocket** TEXT messages (plugin) **or** attribute “writes” to **`client_part2`** runs and JMeter for HTTP only |

**`assignment-baseline-100k-5min.jmx` / `assignment-stress-30min.jmx`** implement the HTTP part of the rubric with **`/metrics` standing in for the “30% write / heavy” slice**; add WebSocket samplers if you need literal chat payloads in JMeter.

State clearly in the PDF how you interpreted **read/write** for grading.
