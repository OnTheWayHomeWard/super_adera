// usage: node get-profile.js <alias from the launch token's sub> [user.full_name,user.contact]
// The optional second argument says which permissions you NEED: if the user did not allow one, you get 403 consent_required with the
// capability and the state (your frontend must then ask the user through the super app; a backend can never open the dialog).
const { call } = require("./adera-client");

const path = "/v1/users/" + process.argv[2] + "/profile" + (process.argv[3] ? "?fields=" + process.argv[3] : "");
call("GET", path).then((r) => console.log(r.status, JSON.stringify(r.body, null, 2)), (e) => { console.error(e.message); process.exit(1); });
