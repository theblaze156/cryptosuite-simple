#!/usr/bin/env python3
"""CryptoSuite Simple — консольный клиент для Termux.

Клиент к ВАШЕМУ серверу crypto_suite (тот же API, что у APK).
Ничего не подписывает и не переводит. Ключи не печатаются и не логируются.
"""
import argparse
import getpass
import json
import os
import stat
import sys
from decimal import Decimal, ROUND_DOWN
from urllib.parse import quote

import requests

CONFIG_PATH = os.path.join(os.path.expanduser("~"), ".config", "cryptosuite", "config.json")
ORDER = ["MATIC", "BNB", "ETH", "USDT", "USDC", "BTC", "LTC", "BCH"]
NO_DATA = "нет данных"


class ApiError(Exception):
    pass


# ---------- config ----------
def load_config(path=None):
    path = path or CONFIG_PATH
    cfg = {}
    try:
        with open(path, encoding="utf-8") as f:
            cfg = json.load(f) or {}
    except (OSError, ValueError):
        cfg = {}
    for env, key in (("CRYPTOSUITE_URL", "url"), ("CRYPTOSUITE_EDGE_KEY", "edge_key"),
                     ("CRYPTOSUITE_API_KEY", "api_key")):
        if os.environ.get(env):
            cfg[key] = os.environ[env]
    return cfg


def save_config(cfg, path=None):
    path = path or CONFIG_PATH
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(cfg, f)
    os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)


# ---------- api ----------
def error_for(code, body):
    try:
        detail = str(json.loads(body).get("detail", ""))
    except Exception:
        detail = ""
    if code == 401:
        return "Неверный или отсутствующий API-ключ (401)."
    if code == 403:
        if "scope" in detail:
            return "У ключа нет прав на это действие (403). Ключ только для чтения."
        return "Неверный ключ доступа (403)."
    if code == 404:
        return "Сервер не знает такой функции (404)."
    if code == 422:
        return "Сервер отклонил данные: " + detail[:150]
    if code == 429:
        return "Слишком много запросов, подождите минуту (429)."
    if 500 <= code <= 599:
        return "Ошибка на сервере (%d). Попробуйте позже." % code
    return "Ошибка %d" % code


class Api:
    def __init__(self, url, edge_key="", api_key=""):
        self.base = (url or "").strip().rstrip("/")
        self.edge_key = edge_key or ""
        self.api_key = api_key or ""

    def headers(self, auth=True):
        h = {"Accept": "application/json"}
        if self.edge_key:  # edge header always (nginx may require it, as in the APK)
            h["X-Crypto-Auth"] = self.edge_key
        if auth:
            if self.api_key:
                if self.api_key.startswith("bsk_"):
                    h["X-API-Key"] = self.api_key
                else:
                    h["Authorization"] = "Bearer " + self.api_key
        return h

    def request(self, method, path, body=None, auth=True):
        if not self.base.startswith(("http://", "https://")):
            raise ApiError("Неверный адрес сервера. Выполните: python main.py setup")
        try:
            r = requests.request(method, self.base + path, headers=self.headers(auth),
                                 json=body, timeout=(10, 60))
        except requests.exceptions.ConnectTimeout:
            raise ApiError("Сервер не отвечает (таймаут).")
        except requests.exceptions.ReadTimeout:
            raise ApiError("Сервер не отвечает (таймаут).")
        except requests.exceptions.ConnectionError:
            raise ApiError("Нет связи с сервером. Проверьте интернет и адрес.")
        except requests.exceptions.RequestException:
            raise ApiError("Нет связи с сервером.")
        if r.status_code >= 400:
            raise ApiError(error_for(r.status_code, r.text))
        if not r.text.strip():
            return {}
        try:
            return r.json()
        except ValueError:
            raise ApiError("Сервер вернул непонятный ответ.")

    def get(self, path, auth=True):
        return self.request("GET", path, auth=auth)

    def health(self): return self.get("/api/health", auth=False)
    def summary(self): return self.get("/api/v1/watchlist/summary")
    def portfolio(self): return self.get("/api/v1/evm/portfolio")
    def watchlist(self): return self.get("/api/v1/watchlist?page=1&page_size=200")
    def audit(self): return self.get("/api/v1/watchlist/audit?min_balance=0&page=1&page_size=200")
    def liquidity_status(self): return self.get("/api/v1/liquidity/status")
    def signals(self): return self.get("/api/v1/liquidity/signals?limit=20")
    def dormant(self): return self.get("/api/v1/liquidity/dormant")

    def delete(self, item_id):
        return self.request("DELETE", "/api/v1/watchlist/" + quote(str(item_id), safe=""))

    def add(self, address, chain=None):
        body = {"text": address.strip()}
        if chain:
            body["default_chain"] = chain
        return self.request("POST", "/api/v1/import/watchlist", body)


# ---------- balances (same logic as Balances.kt) ----------
def norm(sym):
    s = (sym or "").upper()
    if "USDT" in s: return "USDT"
    if "USDC" in s: return "USDC"
    if "MATIC" in s or "POL" in s: return "MATIC"
    if "BNB" in s: return "BNB"
    if "ETH" in s: return "ETH"
    return None


def _dec(v, decimals):
    return Decimal(str(v)).scaleb(-decimals)


def aggregate(summary, portfolio):
    out = {k: Decimal(0) for k in ORDER}
    unavailable = set()
    if summary is None:
        unavailable |= {"BTC", "LTC", "BCH"}
    pc = (summary or {}).get("per_chain") or {}
    for k, sym in (("btc", "BTC"), ("ltc", "LTC"), ("bch", "BCH")):
        r = pc.get(k)
        if not isinstance(r, dict):
            continue
        if not r.get("balance_available", False) and int(r.get("count") or 0) > 0:
            unavailable.add(sym)
        else:
            out[sym] = _dec(int(r.get("balance_sat") or 0), 8)
    seen = set()

    def add_native(addr, cid, symbol, wei):
        key = "%s:%s" % ((addr or "").lower(), cid)
        if key in seen:
            return
        seen.add(key)
        s = norm(symbol)
        if s is None:
            return
        if wei is None:
            unavailable.add(s)
            return
        out[s] += _dec(wei, 18)

    tok_seen = set()
    for a in (portfolio or {}).get("accounts") or []:
        addr = (a.get("account") or {}).get("address") or ""
        for b in a.get("balances") or []:
            cid = int(b.get("chain_id") or 0)
            add_native(addr, cid, b.get("native_symbol") or "", b.get("native_wei"))
            for tk in b.get("tokens") or []:
                s = norm(tk.get("symbol"))
                if s not in ("USDT", "USDC"):
                    continue
                tkey = "%s:%s:%s" % (addr.lower(), cid, (tk.get("address") or "").lower())
                if tkey in tok_seen:
                    continue
                tok_seen.add(tkey)
                if tk.get("balance") is None:
                    unavailable.add(s)
                    continue
                out[s] += _dec(tk["balance"], int(tk.get("decimals", 18)))
    evm = pc.get("evm") or {}
    for a in evm.get("by_account") or []:
        for c in a.get("chains") or []:
            add_native(a.get("address"), int(c.get("chain_id") or 0),
                       c.get("native_symbol", c.get("key", "")),
                       c.get("native_wei") if "native_wei" in c else None)
    if portfolio is None:
        unavailable |= {"USDT", "USDC"}
    for s in unavailable:
        out[s] = None
    return out


def fmt(v):
    if v is None:
        return NO_DATA
    if v == 0:
        return "0"
    q = v.quantize(Decimal("0.00000001"), rounding=ROUND_DOWN).normalize()
    return format(q, "f")


# ---------- commands ----------
def warn_http(url):
    if (url or "").startswith("http://"):
        print("⚠ Соединение без шифрования (http). Используйте только свой сервер; лучше включить HTTPS.",
              file=sys.stderr)


def make_api(cfg):
    if not cfg.get("url"):
        raise ApiError("Сервер не настроен. Выполните: python main.py setup")
    warn_http(cfg["url"])
    return Api(cfg["url"], cfg.get("edge_key", ""), cfg.get("api_key", ""))


def cmd_setup(cfg, args):
    cur = load_config()
    url = input("Адрес сервера (например https://example.com)%s: " %
                (" [%s]" % cur["url"] if cur.get("url") else "")).strip() or cur.get("url", "")
    edge = getpass.getpass("X-Crypto-Auth (Enter — пропустить/оставить): ").strip() or cur.get("edge_key", "")
    key = getpass.getpass("API-ключ bsk_… (Enter — оставить): ").strip() or cur.get("api_key", "")
    if not url.startswith(("http://", "https://")):
        raise ApiError("Адрес должен начинаться с http:// или https://")
    save_config({"url": url, "edge_key": edge, "api_key": key})
    print("Сохранено в %s (права 600). Ключи не выводятся." % CONFIG_PATH)
    warn_http(url)


def cmd_health(cfg, args):
    h = make_api(cfg).health()
    print("Сервер: %s" % h.get("status", "ok"))


def cmd_balance(cfg, args):
    api = make_api(cfg)
    summary = portfolio = None
    try:
        summary = api.summary()
    except ApiError as e:
        print("Сводка: %s" % e, file=sys.stderr)
    try:
        portfolio = api.portfolio()
    except ApiError as e:
        print("Портфель EVM: %s" % e, file=sys.stderr)
    agg = aggregate(summary, portfolio)
    for sym in ORDER:
        v = agg[sym]
        if sym == "BCH" and v is not None and v == 0:
            continue
        print("%-5s %s" % (sym, fmt(v)))
    if summary and isinstance(summary.get("totals"), dict):
        print("Адресов под наблюдением: %s" % summary["totals"].get("addresses", 0))
    print("Только реальные данные сервера. «нет данных» — источник недоступен.")


def cmd_list(cfg, args):
    j = make_api(cfg).watchlist()
    items = j.get("items") or []
    if not items:
        print("Список пуст.")
    for it in items:
        label = it.get("label")
        print("[%s] %s %s%s" % (it.get("id"), str(it.get("chain", "")).upper(), it.get("address"),
                                 "  (%s)" % label if label else ""))
    print("Всего: %s" % j.get("total", len(items)))


def cmd_add(cfg, args):
    print("Добавляйте ТОЛЬКО свои адреса. Приватные ключи и seed-фразы не нужны.")
    r = make_api(cfg).add(args.address, args.chain)
    print("Добавлено: %s, пропущено: %s, ошибок: %s" % (
        r.get("imported", 0), r.get("skipped", 0), int(r.get("invalid", 0) or 0) + int(r.get("rejected", 0) or 0)))


def cmd_remove(cfg, args):
    make_api(cfg).delete(args.id)
    print("Удалено: %s" % args.id)


def cmd_audit(cfg, args):
    j = make_api(cfg).audit()
    print("Проверено адресов: %s" % j.get("scanned", 0))
    items = j.get("items") or []
    if not items:
        print("Нет адресов для проверки.")
    for r in items:
        bal = r.get("balance_display") if r.get("balance_available") else NO_DATA
        print("%s %s  баланс: %s" % (str(r.get("chain", "")).upper(), r.get("address"), bal))
    for n in j.get("notes") or []:
        print("• %s" % n)


def cmd_status(cfg, args):
    api = make_api(cfg)
    h = api.health()
    print("Сервер: %s" % h.get("status", "?"))
    try:
        s = api.liquidity_status()
        print("Отслеживается: %s, спящие: %s, недоступно: %s" % (
            s.get("tracked", 0), s.get("dormant", 0), s.get("unavailable", 0)))
    except ApiError as e:
        print("Статус: %s" % e)
    print("\nПоследние сигналы:")
    try:
        sig = (api.signals().get("items") or [])[:20]
        if not sig:
            print("  Сигналов нет.")
        for o in sig:
            print("  %s %s… %s %s" % (str(o.get("chain") or "—").upper(), str(o.get("address", ""))[:14],
                                      ("%s %s" % (o.get("direction", ""), o.get("asset", ""))).strip(),
                                      str(o.get("created_at", "")).replace("T", " ")[:16]))
    except ApiError as e:
        print("  %s: %s" % (NO_DATA, e))
    print("\nСпящие кошельки:")
    try:
        d = (api.dormant().get("items") or [])[:20]
        if not d:
            print("  Нет.")
        for o in d:
            print("  %s %s" % (str(o.get("chain", "")).upper(), o.get("address")))
    except ApiError as e:
        print("  %s: %s" % (NO_DATA, e))


def build_parser():
    p = argparse.ArgumentParser(prog="main.py", description="CryptoSuite Simple — клиент для вашего сервера")
    sub = p.add_subparsers(dest="cmd")
    sub.add_parser("setup", help="настроить адрес сервера и ключи")
    sub.add_parser("health", help="проверка сервера")
    sub.add_parser("balance", help="балансы")
    sub.add_parser("list", help="список адресов")
    a = sub.add_parser("add", help="добавить свой адрес")
    a.add_argument("address")
    a.add_argument("--chain", choices=["auto", "btc", "ltc", "bch", "evm"], default="auto")
    r = sub.add_parser("remove", help="удалить адрес по id")
    r.add_argument("id")
    sub.add_parser("audit", help="аудит балансов")
    sub.add_parser("status", help="статус и алерты")
    return p


COMMANDS = {"setup": cmd_setup, "health": cmd_health, "balance": cmd_balance, "list": cmd_list,
            "add": cmd_add, "remove": cmd_remove, "audit": cmd_audit, "status": cmd_status}


def main(argv=None):
    p = build_parser()
    args = p.parse_args(argv)
    if not args.cmd:
        p.print_help()
        return 1
    try:
        COMMANDS[args.cmd](load_config(), args)
    except ApiError as e:
        print("Ошибка: %s" % e, file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
