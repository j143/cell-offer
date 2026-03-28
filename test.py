import requests, random, time

HOT_CELL = "8928308280fffff"
URL = f"http://localhost:8080/cells/{HOT_CELL}/offers"

for i in range(1000):
    requests.post(URL, json={
        "driverId": f"d-{i}",
        "riderId": f"r-{random.randint(0, 50)}",
        "priority": random.randint(1, 10),
        "ttlMillis": random.choice([500, 1000, 5000])
    })
    time.sleep(0.001)  # 1000 req/s