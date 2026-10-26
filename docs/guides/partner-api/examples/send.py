import json, sys
from adera_client import call

r = call("POST", "/v1/notifications", {
    "externalId": sys.argv[1], "userId": sys.argv[2], "templateId": sys.argv[3], "params": {"name": "Abebe"}})
print(r.status_code, json.dumps(r.json(), indent=2))
print(call("GET", "/v1/notifications/" + sys.argv[1]).json())
