import json, sys
from adera_client import call

# usage: python get_profile.py <alias from the launch token's sub> [user.full_name,user.contact]
# The optional second argument says which permissions you NEED: if the user did not allow one, you get 403 consent_required with the
# capability and the state (your frontend must then ask the user through the super app; a backend can never open the dialog).
path = "/v1/users/" + sys.argv[1] + "/profile" + ("?fields=" + sys.argv[2] if len(sys.argv) > 2 else "")
r = call("GET", path)
print(r.status_code, json.dumps(r.json(), indent=2, ensure_ascii=False))
