"""Lag proxy for test 28: forwards a Minecraft connection with added delay and jitter.

    python -I lagproxy.py [--listen 25570] [--target 25565] [--delay 1000] [--jitter 500]

Connect the client to localhost:<listen>. Every chunk of data in both directions is held for
delay +/- jitter milliseconds, in order (TCP order is kept, so jitter shows up as bursts, like a
real congested connection). Stop with Ctrl+C. Standard library only.
"""
import argparse
import asyncio
import random
import time


async def pipe(reader, writer, delay_ms, jitter_ms, label):
    queue = asyncio.Queue()

    async def receive():
        while True:
            data = await reader.read(65536)
            hold = max(0.0, delay_ms + random.uniform(-jitter_ms, jitter_ms)) / 1000
            await queue.put((time.monotonic() + hold, data))
            if not data:
                return

    async def send():
        last_due = 0.0
        while True:
            due, data = await queue.get()
            due = max(due, last_due)  # never reorder
            last_due = due
            wait = due - time.monotonic()
            if wait > 0:
                await asyncio.sleep(wait)
            if not data:
                writer.close()
                return
            writer.write(data)
            await writer.drain()

    try:
        await asyncio.gather(receive(), send())
    except (ConnectionError, asyncio.IncompleteReadError):
        pass
    finally:
        writer.close()
    print(f"[{label}] closed")


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen", type=int, default=25570)
    parser.add_argument("--target", type=int, default=25565)
    parser.add_argument("--delay", type=int, default=1000, help="ms each way")
    parser.add_argument("--jitter", type=int, default=500, help="+/- ms")
    args = parser.parse_args()

    async def handle(client_reader, client_writer):
        server_reader, server_writer = await asyncio.open_connection("127.0.0.1", args.target)
        print(f"client connected, adding {args.delay} +/- {args.jitter} ms each way")
        await asyncio.gather(
            pipe(client_reader, server_writer, args.delay, args.jitter, "client->server"),
            pipe(server_reader, client_writer, args.delay, args.jitter, "server->client"),
        )

    server = await asyncio.start_server(handle, "127.0.0.1", args.listen)
    print(f"lag proxy on localhost:{args.listen} -> localhost:{args.target}")
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
