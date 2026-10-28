# CryptoSuite Simple — консольный клиент (Termux)

Это **клиент** для вашего собственного сервера crypto_suite, тот же API, что у APK.
Локальный сервер, `uvicorn` и т.п. **не нужны**: сервер уже работает у вас. APK и этот CLI — два клиента к одному бэкенду.
Только чтение и управление списком наблюдения: никаких подписей, переводов и приватных ключей.

## Установка в Termux

```sh
pkg install python git
git clone https://github.com/theblaze156/cryptosuite-simple.git
cd cryptosuite-simple/termux
pip install -r requirements.txt
```

Обновление: `git pull origin main && cd termux && pip install -r requirements.txt`.

## Настройка

```sh
python main.py setup
```

Спросит адрес сервера, `X-Crypto-Auth` (необязательно) и API-ключ `bsk_…` (ввод скрыт).
Сохраняется в `~/.config/cryptosuite/config.json` с правами 600. Либо через переменные окружения (имеют приоритет):

```sh
export CRYPTOSUITE_URL=https://example.com
export CRYPTOSUITE_EDGE_KEY=example-edge-value
export CRYPTOSUITE_API_KEY=bsk_EXAMPLE_NOT_A_REAL_KEY
```

## Команды

```sh
python main.py health          # сервер жив?
python main.py balance         # MATIC, BNB, ETH, USDT, USDC, BTC, LTC, BCH
python main.py list            # адреса с id
python main.py add 0x000000000000000000000000000000000000dEaD --chain evm   # только СВОИ адреса
python main.py remove 42       # удалить по id
python main.py audit           # балансы по каждому адресу
python main.py status          # статус, сигналы, спящие кошельки
```

`0` — баланс пустой, «нет данных» — источник недоступен (цифры не выдумываются).
При `http://` выводится предупреждение: лучше включить HTTPS. Ключи никогда не печатаются.

## Тесты

```sh
python -m unittest -v test_main
```
