const { call } = require("./adera-client");
(async () => {
  const [externalId, userId, templateId] = process.argv.slice(2);
  console.log(await call("POST", "/v1/notifications", { externalId, userId, templateId, params: { name: "Abebe" } }));
  console.log(await call("GET", "/v1/notifications/" + externalId));
})();
