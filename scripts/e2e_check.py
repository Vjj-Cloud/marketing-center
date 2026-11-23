"""端到端验证：鉴权 → 限流 → 建活动 → 预热 → 并发抢购 → 消费落库 → 对账 → 预留归还。

全部请求走 8081 网关（真实链路：网关鉴权 → 业务服务验签 → Redis/MQ/DB），
不使用直连端口，否则测不到鉴权与限流。

判定标准：
  1. 无 token 被拒 401；直连伪造 X-User-Id 被拒 401
  2. 单用户高频请求触发 429
  3. 成功笔数 == min(并发人数, 库存)，不超卖；Redis 库存 == 库存 - 成功笔数
  4. 每个抢到的用户都能在订单服务查到订单号，且订单号不重复
  5. 活动账本 available_stock + 订单数 == total_stock
  6. 商品库存：发布时扣 reserved_stock（预留），落库时不再二次扣减，
     活动结束后只归还未售部分（第七轮评审 P2 补的断言）
     想专门验证「只归还未售部分」，就把并发人数调到小于库存，
     例如 3 人抢 5 件：发布后 -5，售出 3 件后归还 2 件。

用法：
  python e2e_check.py [并发数] [库存] [用户起始ID] [商品ID] [秒杀价] [活动时长秒] [等待归还秒]
  例：python e2e_check.py 20 5 80000 2 199.00 60 180
      —— 活动开 60 秒后结束，再等 180 秒看预留库存有没有正确归还。
  最后一个参数不传（或 0）时跳过归还验证，只验证到「落库后商品库存不再变化」。
"""

import json
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta

GATEWAY = "http://127.0.0.1:8081"
SECKILL_DIRECT = "http://127.0.0.1:8082"
ORDER_DIRECT = "http://127.0.0.1:8083"

CONCURRENCY = int(sys.argv[1]) if len(sys.argv) > 1 else 200
STOCK = int(sys.argv[2]) if len(sys.argv) > 2 else 50
USER_BASE = int(sys.argv[3]) if len(sys.argv) > 3 else 80000
# 商品和价格可参数化：同一商品连续跑会撞「时间重叠」校验（这本身是 P1-5 的修复生效），
# 换一个商品就能继续验证。价格必须低于商品原价，否则会被创建接口拒掉。
PRODUCT_ID = int(sys.argv[4]) if len(sys.argv) > 4 else 2
SECKILL_PRICE = float(sys.argv[5]) if len(sys.argv) > 5 else 199.00
# 活动持续时长：默认 30 分钟，够跑完并发；想验证「结束后归还预留」就调短（如 60 秒）
END_AFTER = int(sys.argv[6]) if len(sys.argv) > 6 else 1800
# 等待归还的上限秒数。归还依赖两个 30 秒节拍的定时任务（结束状态 + 释放），
# 留 2 倍余量比较稳；不传就跳过这一步。
RELEASE_WAIT = int(sys.argv[7]) if len(sys.argv) > 7 else 0

results = {}
lock = threading.Lock()
latencies = []


def http(method, url, body=None, token=None, headers=None, timeout=20):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "ignore")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}
    except Exception as e:
        return 0, {"error": str(e)}


def mint_token(uid, role="USER"):
    status, body = http("POST", f"{GATEWAY}/api/auth/token", {"userId": uid, "role": role})
    if status != 200:
        raise RuntimeError(f"取 token 失败 uid={uid} role={role} status={status} body={body}")
    return body["data"]["token"]


def section(title):
    print("\n" + "=" * 70)
    print(title)
    print("=" * 70)


def test_auth():
    section("1. 鉴权")
    status, body = http("GET", f"{GATEWAY}/api/seckill/activity/list")
    print(f"  无 token              → HTTP {status} {body.get('message')}")
    assert status == 401, "无 token 竟然通过了"

    status, body = http("GET", f"{SECKILL_DIRECT}/api/seckill/activity/1/stock",
                        headers={"X-User-Id": "1"})
    print(f"  直连 8082 伪造身份     → HTTP {status} {body.get('message')}")
    assert status == 401, "直连伪造身份竟然通过了"

    status, body = http("GET", f"{ORDER_DIRECT}/api/order/result?activityId=1",
                        headers={"X-User-Id": "1"})
    print(f"  直连 8083 伪造身份     → HTTP {status} {body.get('message')}")
    assert status == 401, "直连伪造身份竟然通过了"

    token = mint_token(1001)
    status, body = http("GET", f"{GATEWAY}/api/auth/me", token=token)
    print(f"  合法 token 查身份      → HTTP {status} userId={body.get('data', {}).get('userId')}")
    assert status == 200 and body["data"]["userId"] == 1001, "网关注入的身份不对"
    assert body["data"]["role"] == "USER", "普通用户角色不对"

    admin = mint_token(9001, "ADMIN")
    status, body = http("GET", f"{GATEWAY}/api/auth/me", token=admin)
    assert status == 200 and body["data"]["role"] == "ADMIN", "管理员角色不对"

    # 直连伪造角色头：签名不含 role，改了角色签名就对不上
    status, body = http("POST", f"{SECKILL_DIRECT}/api/seckill/activity/999999/publish",
                        headers={"X-User-Id": "1", "X-User-Role": "ADMIN"})
    print(f"  直连伪造 ADMIN 角色    → HTTP {status}")
    assert status == 401, "直连伪造角色竟然通过了"
    return token, admin


def test_authorization(user_token, admin_token):
    section("1.5 授权（只有认证没有授权是上一版的漏洞）")
    body = {"productId": PRODUCT_ID, "title": "越权测试", "seckillPrice": 9.00,
            "totalStock": 1, "startTime": _fmt(600), "endTime": _fmt(3600)}

    status, payload = http("POST", f"{GATEWAY}/api/seckill/activity", body, token=user_token)
    print(f"  普通用户建活动         → HTTP {status} {payload.get('message')}")
    assert status == 403, f"普通用户竟然能建活动：{status}"

    # 关键：不存在的活动 id 也必须先返回 403 而不是 404/400，
    # 否则等于告诉攻击者「这个 id 存不存在」
    status, payload = http("POST", f"{GATEWAY}/api/seckill/activity/999999/publish", token=user_token)
    print(f"  普通用户发布不存在活动 → HTTP {status} {payload.get('message')}")
    assert status == 403, f"权限判断晚于业务校验：{status}"

    status, payload = http("POST", f"{GATEWAY}/api/seckill/activity/999999/publish", token=admin_token)
    print(f"  管理员发布不存在活动   → HTTP {status} {payload.get('message')}")
    assert status == 404, f"管理员过了授权层后应得到 404：{status}"

    status, payload = http("GET", f"{GATEWAY}/api/seckill/admin/outbox/review", token=user_token)
    print(f"  普通用户查待复核列表   → HTTP {status}")
    assert status == 403, "普通用户竟然能看运维列表"

    status, payload = http("GET", f"{GATEWAY}/api/seckill/admin/outbox/review", token=admin_token)
    print(f"  管理员查待复核列表     → HTTP {status} 共 {len(payload.get('data') or [])} 条")
    assert status == 200, f"管理员查询失败：{payload}"


def test_bad_request(token):
    section("2. 客户端错误不能变成 500")
    cases = [
        ("非法 JSON", "POST", f"{GATEWAY}/api/seckill/activity", "{not json", None),
        ("路径参数类型错", "GET", f"{GATEWAY}/api/order/result?activityId=abc", None, None),
        ("缺少必填参数", "GET", f"{GATEWAY}/api/order/result", None, None),
        ("接口不存在", "GET", f"{GATEWAY}/api/order/results?activityId=1", None, None),
        ("方法不支持", "GET", f"{GATEWAY}/api/seckill/activity", None, token),
        ("秒杀价不低于原价", "POST", f"{GATEWAY}/api/seckill/activity",
         {"productId": PRODUCT_ID, "title": "错误价格", "seckillPrice": 999.00,
          "totalStock": 10, "startTime": _fmt(120), "endTime": _fmt(7200)}, None),
    ]
    for name, method, url, body, tk in cases:
        tk = tk or token
        if isinstance(body, str):
            # 故意发语法错误的 JSON
            req = urllib.request.Request(url, data=body.encode(), method=method)
            req.add_header("Content-Type", "application/json")
            req.add_header("Authorization", "Bearer " + tk)
            try:
                with urllib.request.urlopen(req, timeout=10) as resp:
                    status, payload = resp.status, json.loads(resp.read())
            except urllib.error.HTTPError as e:
                status, payload = e.code, json.loads(e.read().decode() or "{}")
        else:
            status, payload = http(method, url, body, token=tk)
        print(f"  {name:<16} → HTTP {status} {str(payload.get('message'))[:40]}")
        assert 400 <= status < 500, f"{name} 返回了 {status}，客户端错误被当成服务端故障"


def test_rate_limit():
    section("3. 单用户限流（replenishRate=20/s, burst=20）")
    uid = 5555
    token = mint_token(uid)
    codes = {}

    def hit(_):
        status, _body = http("POST", f"{GATEWAY}/api/seckill/order/1", token=token)
        with lock:
            codes[status] = codes.get(status, 0) + 1

    with ThreadPoolExecutor(max_workers=60) as pool:
        list(pool.map(hit, range(60)))

    print(f"  60 次突发请求结果：{json.dumps(codes, sort_keys=True)}")
    ok = codes.get(200, 0)
    limited = codes.get(429, 0)
    print(f"  通过 {ok} 次，被限流 {limited} 次")
    assert limited > 0, "限流没有生效，用户的 60 次请求全部通过"
    return codes


def _fmt(offset_seconds):
    return (datetime.now() + timedelta(seconds=offset_seconds)).strftime("%Y-%m-%d %H:%M:%S")


def stock_view(activity_id, token):
    """对账视图：活动账本 + Redis + 已购人数 + 商品库存，一次快照取全。

    必须一次请求取全：分几次请求拿到的是不同时刻的数字，
    而「商品库存」和「活动账本」要放在一起比较才有意义。
    """
    status, body = http("GET", f"{GATEWAY}/api/seckill/activity/{activity_id}/stock", token=token)
    assert status == 200, f"对账视图查询失败 {status} {body}"
    return body["data"]


def create_activity(token):
    section("4. 创建 → 发布 → 预热")
    # 开始时间留 15 秒：既满足「未开始才能预热」的安全约束，
    # 又不用改数据库造假数据
    start_at = datetime.now() + timedelta(seconds=15)
    payload = {
        "productId": PRODUCT_ID,
        "title": f"限时秒杀 {datetime.now().strftime('%H%M%S')}",
        "seckillPrice": SECKILL_PRICE,
        "totalStock": STOCK,
        "startTime": start_at.strftime("%Y-%m-%d %H:%M:%S"),
        "endTime": (start_at + timedelta(seconds=END_AFTER)).strftime("%Y-%m-%d %H:%M:%S"),
    }
    status, body = http("POST", f"{GATEWAY}/api/seckill/activity", payload, token=token)
    print(f"  创建活动 → HTTP {status} id={body.get('data')}")
    assert status == 200, f"创建失败 {body}"
    activity_id = body["data"]

    # 发布前先记基线：此刻还没预留，productStock 就是这场活动开始前的商品真实库存。
    # 后面所有关于「扣了多少、还了多少」的判断都以它为基准。
    before_publish = stock_view(activity_id, token)
    baseline_stock = before_publish["productStock"]
    print(f"  发布前商品库存 → {baseline_stock}（预留 {before_publish.get('reservedStock')} 件）")
    assert not before_publish.get("reservedStock"), "草稿状态不该预留商品库存"

    status, body = http("POST", f"{GATEWAY}/api/seckill/activity/{activity_id}/publish", token=token)
    print(f"  发布活动 → HTTP {status}")
    assert status == 200, f"发布失败 {body}"

    # ★ 第六轮的核心不变量：货在发布时就从 product.stock 扣走了，不是下单时扣。
    after_publish = stock_view(activity_id, token)
    print(f"  发布后商品库存 → {after_publish['productStock']}（预留 {after_publish['reservedStock']} 件）")
    assert after_publish["reservedStock"] == STOCK, \
        f"发布后应预留 {STOCK} 件，实际 {after_publish['reservedStock']}"
    assert after_publish["productStock"] == baseline_stock - STOCK, \
        f"发布应把商品库存从 {baseline_stock} 扣到 {baseline_stock - STOCK}，" \
        f"实际 {after_publish['productStock']}"

    status, body = http("POST", f"{GATEWAY}/api/seckill/activity/{activity_id}/warmup", token=token)
    print(f"  预热库存 → HTTP {status} {body.get('data')}")
    assert status == 200, f"预热失败 {body}"

    status, body = http("GET", f"{GATEWAY}/api/seckill/activity/{activity_id}/stock", token=token)
    print(f"  预热后视图 → {body.get('data')}")
    assert body["data"]["redisStock"] == STOCK, "预热后的 Redis 库存不等于活动总库存"
    return activity_id, start_at, baseline_stock


def test_warmup_guard(token, activity_id):
    section("5. 预热幂等（未开始时可重复预热，但不会重置库存）")
    status, body = http("POST", f"{GATEWAY}/api/seckill/activity/{activity_id}/warmup", token=token)
    print(f"  重复预热 → HTTP {status} {body.get('data') or body.get('message')}")
    assert status == 200, f"重复预热失败 {body}"
    assert body["data"]["written"] is False, "重复预热竟然重置了库存"
    assert body["data"]["redisStock"] == STOCK, "重复预热后库存变了"


def run_concurrent(activity_id, tokens, start_at):
    section("6. 并发抢购")
    wait = (start_at - datetime.now()).total_seconds() + 2
    if wait > 0:
        print(f"  等待活动开始 {wait:.0f}s ...")
        time.sleep(wait)

    barrier = threading.Barrier(len(tokens))

    def worker(item):
        uid, token = item
        barrier.wait()
        t0 = time.time()
        status, body = http("POST", f"{GATEWAY}/api/seckill/order/{activity_id}", token=token)
        cost = (time.time() - t0) * 1000
        if status == 200:
            key = (body.get("data") or {}).get("status", "UNKNOWN")
        else:
            key = f"HTTP_{status}"
        with lock:
            results[key] = results.get(key, 0) + 1
            latencies.append(cost)

    t0 = time.time()
    with ThreadPoolExecutor(max_workers=len(tokens)) as pool:
        list(pool.map(worker, tokens))
    elapsed = time.time() - t0

    print(f"  并发 {len(tokens)} 人，耗时 {elapsed:.2f}s，QPS≈{len(tokens) / elapsed:.0f}")
    print(f"  结果分布：{json.dumps(results, ensure_ascii=False, sort_keys=True)}")
    latencies.sort()
    if latencies:
        print(f"  延迟：P50={latencies[len(latencies) // 2]:.0f}ms "
              f"P99={latencies[int(len(latencies) * 0.99) - 1]:.0f}ms "
              f"max={latencies[-1]:.0f}ms")
    return results


def verify(activity_id, tokens, success_count, admin_token, baseline_stock):
    section("7. 消费结果与对账")

    def query_one(item):
        uid, token = item
        status, body = http("GET", f"{GATEWAY}/api/order/result?activityId={activity_id}", token=token)
        data = body.get("data") or {}
        key = data.get("status", f"HTTP_{status}")
        return uid, key, data.get("orderNo")

    # 异步消费是最终一致，不能固定睡三秒后只查一次。这里在截止时间内轮询，
    # 直到成功订单达到预期，避免机器负载稍高就把正确结果误判成失败。
    deadline = time.time() + 20
    states = {}
    order_nos = []
    while True:
        states = {}
        order_nos = []
        with ThreadPoolExecutor(max_workers=min(32, len(tokens))) as pool:
            rows = list(pool.map(query_one, tokens))
        for _uid, key, order_no in rows:
            states[key] = states.get(key, 0) + 1
            if order_no:
                order_nos.append(order_no)
        if len(order_nos) >= success_count or time.time() >= deadline:
            break
        time.sleep(0.5)

    print(f"  查询结果分布：{json.dumps(states, ensure_ascii=False, sort_keys=True)}")
    print(f"  拿到订单号 {len(order_nos)} 个，示例：{order_nos[:3]}")
    assert len(set(order_nos)) == len(order_nos), "出现重复订单号"

    # 对账视图含 Redis 实时库存，属于运维信息，需要管理员
    stock = stock_view(activity_id, admin_token)
    print(f"  库存对账视图：{json.dumps(stock, ensure_ascii=False)}")

    # 全部卖完时 sold = STOCK；人比库存少时 sold = 人数。
    # 用这个「必然结果」当基准，而不是写死 STOCK——
    # 否则想验证「部分售出后只归还未售部分」时必须改脚本。
    expected_sold = min(len(tokens), STOCK)
    ok = True
    if stock["redisStock"] != STOCK - expected_sold:
        print(f"  ✗ Redis 库存应为 {STOCK - expected_sold}，实际 {stock['redisStock']}")
        ok = False
    if stock["boughtCount"] != expected_sold:
        print(f"  ✗ 已抢人数应为 {expected_sold}，实际 {stock['boughtCount']}")
        ok = False
    if stock["dbAvailableStock"] != stock["totalStock"] - len(order_nos):
        print(f"  ✗ 账本库存 {stock['dbAvailableStock']} != 总库存 {stock['totalStock']} - 订单 {len(order_nos)}")
        ok = False
    # ★ 第七轮评审 P2：商品库存必须只被扣过一次（发布时预留）。
    #   这里如果又少了订单数那么多，就是「预留 + 逐单扣减」双扣——
    #   商品页库存会比真实少卖件数那么多，且活动结束后还回来的数量也对不上。
    if stock.get("productStock") != baseline_stock - STOCK:
        print(f"  ✗ 商品库存应为 {baseline_stock - STOCK}（发布时已预留 {STOCK} 件），"
              f"实际 {stock.get('productStock')}——落库时又扣了一次")
        ok = False
    if success_count != expected_sold:
        print(f"  ✗ 成功笔数 {success_count} != 预期 {expected_sold}")
        ok = False
    if len(order_nos) != expected_sold:
        print(f"  ✗ 落库订单 {len(order_nos)} 笔 != 预期 {expected_sold}")
        ok = False
    print(f"  {'PASS：库存、买家数、订单数、商品库存四者一致，无超卖、无双扣' if ok else 'FAIL：对账不一致'}")
    return ok, len(order_nos)


def check_release(activity_id, admin_token, baseline_stock, sold, wait_seconds):
    """活动结束后，只把「未售」那部分还给商品库存。

    这是第六轮最容易被做错的一步，也是 e2e 脚本之前完全没覆盖的一段：
      · 不归还 → 商品永久少卖已结束活动的剩余量；
      · 归还早了 → 迟到订单用的库存已经给了下一场，同一件货卖两次；
      · 全额归还（而不是只还未售）→ 已卖出去的那部分凭空多出来，直接超卖。
    归还由两个 30 秒节拍的定时任务驱动（先置结束状态，再释放），所以要等一会儿。
    """
    section("8. 活动结束后归还预留库存")
    if wait_seconds <= 0:
        print("  跳过：未指定等待时长（第 7 个参数）。")
        print("  想验证就把活动时长调短，例如：python e2e_check.py 20 5 80000 2 199.00 60 180")
        return True

    deadline = time.time() + wait_seconds
    view = {}
    while time.time() < deadline:
        view = stock_view(activity_id, admin_token)
        if view.get("reservationReleased"):
            break
        time.sleep(3)

    if not view.get("reservationReleased"):
        print(f"  ✗ 等了 {wait_seconds}s 仍未归还：{json.dumps(view, ensure_ascii=False)}")
        print("    排查：活动是否已进入结束状态(2)？outbox 是否还有 0/1/4/5/7 的记录？")
        return False

    expect = baseline_stock - sold
    print(f"  已归还：商品库存 {view['productStock']}，期望 {expect}"
          f"（基线 {baseline_stock} - 售出 {sold}）")
    print(f"  活动账本：total={view['totalStock']} available={view['dbAvailableStock']}"
          f" 预留={view['reservedStock']}")
    ok = True
    if view["productStock"] != expect:
        print(f"  ✗ 归还数量不对：多还或少还了 {view['productStock'] - expect} 件")
        ok = False
    if view["dbAvailableStock"] != view["totalStock"] - sold:
        print(f"  ✗ 账本未售数量 {view['dbAvailableStock']} != 总库存 {view['totalStock']} - 已售 {sold}")
        ok = False
    print(f"  {'PASS：只归还未售部分，已售出的那部分没有被重复归还' if ok else 'FAIL：归还逻辑不正确'}")
    return ok


def main():
    user_token, admin_token = test_auth()
    test_authorization(user_token, admin_token)
    # 建活动/发布/预热/对账都需要管理员，所以这一组用例走 admin
    test_bad_request(admin_token)
    test_rate_limit()
    activity_id, start_at, baseline_stock = create_activity(admin_token)
    test_warmup_guard(admin_token, activity_id)

    section("6.0 批量取 token")
    users = [(USER_BASE + i, None) for i in range(CONCURRENCY)]
    with ThreadPoolExecutor(max_workers=32) as pool:
        minted = list(pool.map(lambda u: (u[0], mint_token(u[0])), users))
    print(f"  已取 {len(minted)} 个 token")

    run_concurrent(activity_id, minted, start_at)
    ok, sold = verify(activity_id, minted, results.get("QUEUED", 0), admin_token, baseline_stock)
    ok = check_release(activity_id, admin_token, baseline_stock, sold, RELEASE_WAIT) and ok
    print(f"\n活动 ID = {activity_id}（可用 mysql 复核 seckill_activity / seckill_order / product）")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
