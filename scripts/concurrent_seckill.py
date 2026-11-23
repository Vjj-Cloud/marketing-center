"""经网关的轻量并发抢购检查。

与 e2e_check.py 的区别：本脚本不创建活动，只对已有活动压并发，
适合快速回归库存原子性和网关限流。所有业务请求都经过 8081，
不再直连服务或伪造 X-User-Id。

用法：
    python concurrent_seckill.py [activityId] [concurrency] [userIdBase]
"""

import json
import sys
import threading
import time
import urllib.error
import urllib.request

GATEWAY = "http://127.0.0.1:8081"
ACTIVITY_ID = int(sys.argv[1]) if len(sys.argv) > 1 else 1
CONCURRENCY = int(sys.argv[2]) if len(sys.argv) > 2 else 200
USER_ID_BASE = int(sys.argv[3]) if len(sys.argv) > 3 else 90000

results = {}
lock = threading.Lock()
barrier = threading.Barrier(CONCURRENCY)
latencies = []


def call(path, method="GET", token=None, body=None, timeout=30):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(f"{GATEWAY}{path}", data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def mint_token(user_id, role="USER"):
    body = call("/api/auth/token", "POST", body={"userId": user_id, "role": role})
    return body["data"]["token"]


def stock_view(admin_token):
    return call(f"/api/seckill/activity/{ACTIVITY_ID}/stock", token=admin_token)["data"]


def worker(item):
    _user_id, token = item
    barrier.wait()
    started = time.time()
    try:
        body = call(f"/api/seckill/order/{ACTIVITY_ID}", "POST", token=token)
        key = (body.get("data") or {}).get("status", "UNKNOWN")
    except urllib.error.HTTPError as e:
        key = f"HTTP_{e.code}"
    except Exception:
        key = "ERROR"
    cost = (time.time() - started) * 1000
    with lock:
        results[key] = results.get(key, 0) + 1
        latencies.append(cost)


def main():
    admin_token = mint_token(999999, "ADMIN")
    before = stock_view(admin_token)
    print(f"抢购前: 总库存={before['totalStock']} redis={before.get('redisStock')} "
          f"已抢={before.get('boughtCount')}")

    users = [(USER_ID_BASE + i, mint_token(USER_ID_BASE + i)) for i in range(CONCURRENCY)]
    threads = [threading.Thread(target=worker, args=(item,)) for item in users]
    started = time.time()
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    elapsed = time.time() - started

    after = stock_view(admin_token)
    print(f"抢购后: redis={after.get('redisStock')} 已抢={after.get('boughtCount')}")
    print(f"并发 {CONCURRENCY} 人，耗时 {elapsed:.2f}s，QPS≈{CONCURRENCY / elapsed:.0f}")
    print("结果分布:", json.dumps(results, ensure_ascii=False, sort_keys=True))

    latencies.sort()
    if latencies:
        p50 = latencies[len(latencies) // 2]
        p99 = latencies[int(len(latencies) * 0.99) - 1]
        print(f"延迟: P50={p50:.0f}ms P99={p99:.0f}ms max={latencies[-1]:.0f}ms")

    queued = results.get("QUEUED", 0)
    remain = after.get("redisStock")
    expected = before.get("redisStock")
    if remain == 0 and queued == expected:
        print(f"PASS：成功 {queued} 笔正好等于剩余库存，Redis 归零，无超卖")
        return 0
    if remain is not None and remain < 0:
        print(f"FAIL：库存为负 ({remain})，发生超卖")
        return 1
    print(f"CHECK：成功 {queued} 笔，剩余 {remain}，请人工核对")
    return 1


if __name__ == "__main__":
    sys.exit(main())
