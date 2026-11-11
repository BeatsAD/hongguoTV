import { test } from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { createServer } from "node:http";
import { once } from "node:events";
import { fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";
import vm from "node:vm";

const require = createRequire(import.meta.url);
const serverRequire = createRequire(
  new URL("../server/package.json", import.meta.url)
);
const merge = require("deepmerge");
const shell = require("shell-quote");

test("shell quoting rejects line terminators after a comment token", () => {
  for (const separator of ["\n", "\r", "\u2028", "\u2029"]) {
    assert.throws(
      () =>
        shell.quote([
          "printf",
          "control",
          { comment: "note" },
          `text${separator}printf injected`,
        ]),
      TypeError
    );
  }
  const words = ["printf", "%s", "spaces and 'quotes'", "hello世界"];
  assert.deepEqual(shell.parse(shell.quote(words)), words);
});

test("merging untrusted source keys cannot change the returned prototype", () => {
  for (const target of [{}, Object.create(null), { session: null }]) {
    for (const encodedKey of ["__proto__", "__pr\\u006fto__"]) {
      const source = JSON.parse(
        `{"${encodedKey}":{"securityProbe":true},"normal":1}`
      );
      const result = merge(target, source);
      assert.equal(result.securityProbe, undefined);
      assert.equal(Object.getPrototypeOf(result), Object.prototype);
      assert.equal(result.normal, 1);
      assert.equal({}.securityProbe, undefined);
    }
  }
});

test("copying target keys and nested objects also filters prototype setters", () => {
  const target = JSON.parse('{"__proto__":{"securityProbe":true},"normal":1}');
  const result = merge(target, {});
  assert.equal(result.securityProbe, undefined);
  assert.equal(Object.getPrototypeOf(result), Object.prototype);
  const nested = merge(
    { session: Object.create(null) },
    JSON.parse(
      '{"session":{"__proto__":{"securityProbe":true},"enabled":true}}'
    )
  );
  assert.equal(nested.session.securityProbe, undefined);
  assert.equal(nested.session.enabled, true);
  let reads = 0;
  const getterSource = Object.defineProperty({}, "__proto__", {
    enumerable: true,
    get() {
      reads++;
      return { securityProbe: true };
    },
  });
  assert.deepEqual(merge(Object.create(null), getterSource), {});
  assert.equal(reads, 0);
});

test("deep merging retains arrays, symbols, custom merging and CLI configuration", () => {
  const symbol = Symbol("configuration");
  const result = merge(
    { nested: { first: 1 }, list: [1], [symbol]: { first: 1 } },
    { nested: { second: 2 }, list: [2], [symbol]: { second: 2 } }
  );
  assert.deepEqual(result.nested, { first: 1, second: 2 });
  assert.deepEqual(result.list, [1, 2]);
  assert.deepEqual(result[symbol], { first: 1, second: 2 });
  assert.deepEqual(merge.all([{ a: 1 }, { b: 2 }]), { a: 1, b: 2 });
  assert.deepEqual(
    merge(
      { port: { value: 1 } },
      { port: { value: 2 } },
      {
        customMerge: (key) =>
          key === "port" ? (_a, b) => ({ value: b.value + 1 }) : undefined,
      }
    ).port,
    { value: 3 }
  );
  const cliMerge =
    require("@react-native-community/cli-config/build/merge").default;
  assert.deepEqual(
    cliMerge(
      { platforms: ["android"], nested: { first: 1 } },
      { platforms: ["ios"], nested: { second: 2 } }
    ),
    { platforms: ["ios"], nested: { first: 1, second: 2 } }
  );
});

test("React Native CLI still reads the Android launcher activity", () => {
  const getMainActivity =
    require("@react-native-community/cli-config-android/build/config/getMainActivity").default;
  const manifest = fileURLToPath(
    new URL("../android/app/src/main/AndroidManifest.xml", import.meta.url)
  );
  assert.equal(getMainActivity(manifest), ".MainActivity");
  const { XMLParser } = require("fast-xml-parser");
  const parsed = new XMLParser({ ignoreAttributes: false }).parse(
    '<Workspace><FileRef location="group:App.xcodeproj"/></Workspace>'
  );
  assert.equal(parsed.Workspace.FileRef["@_location"], "group:App.xcodeproj");
});

test("query string comma arrays tolerate non-callable toString properties", () => {
  const qs = require("qs");
  assert.doesNotThrow(() =>
    qs.stringify({ value: [{ toString: 1 }] }, { arrayFormat: "comma" })
  );
  assert.equal(
    qs.stringify({ value: ["one", "two"] }, { arrayFormat: "comma" }),
    "value=one%2Ctwo"
  );
});

async function listen(t, handler) {
  const server = createServer(handler);
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  t.after(() => {
    server.closeAllConnections();
    server.close();
  });
  return `http://127.0.0.1:${server.address().port}`;
}

for (const [name, load] of [
  ["root", require],
  ["standalone server", serverRequire],
]) {
  test(`${name} keeps standard HTTP proxy and NO_PROXY behavior`, () => {
    const packageFile = fileURLToPath(
      new URL(
        name === "root" ? "../package.json" : "../server/package.json",
        import.meta.url
      )
    );
    const script = `
      const load = require('node:module').createRequire(process.argv[1]);
      const proxy = load('proxy-from-env').getProxyForUrl;
      process.stdout.write(JSON.stringify([
        'http://api.example.test/path', 'https://api.example.test/path',
        'http://127.0.0.1:8787/path', 'http://localhost:8787/path',
        'https://video.bypass.example.test/path'
      ].map(proxy)));
    `;
    const output = execFileSync(process.execPath, ["-e", script, packageFile], {
      encoding: "utf8",
      env: {
        ...process.env,
        http_proxy: "http://127.0.0.1:3128",
        HTTP_PROXY: "http://127.0.0.1:3128",
        https_proxy: "http://127.0.0.1:3129",
        HTTPS_PROXY: "http://127.0.0.1:3129",
        no_proxy: "127.0.0.1,localhost,.bypass.example.test",
        NO_PROXY: "127.0.0.1,localhost,.bypass.example.test",
        all_proxy: "",
        ALL_PROXY: "",
      },
    });
    assert.deepEqual(JSON.parse(output), [
      "http://127.0.0.1:3128",
      "http://127.0.0.1:3129",
      "",
      "",
      "",
    ]);
  });

  test(`${name} redirects strip cross-realm custom credential headers`, async (t) => {
    const destination = await listen(t, (request, response) => {
      response.setHeader("Content-Type", "application/json");
      response.end(JSON.stringify(request.headers));
    });
    const source = await listen(t, (_request, response) => {
      response.writeHead(302, { Location: destination });
      response.end();
    });
    const { http } = load("follow-redirects");
    const headers = await new Promise((resolve, reject) => {
      const request = http.get(
        source,
        {
          headers: {
            "x-api-key": "private-test-token",
            "x-harmless": "retained",
          },
          sensitiveHeaders: vm.runInNewContext("['x-api-key']"),
        },
        (response) => {
          let body = "";
          response.setEncoding("utf8");
          response.on("data", (chunk) => {
            body += chunk;
          });
          response.on("end", () => {
            try {
              resolve(JSON.parse(body));
            } catch (error) {
              reject(error);
            }
          });
        }
      );
      request.on("error", reject);
      request.setTimeout(3000, () =>
        request.destroy(new Error("Test request timed out"))
      );
    });
    assert.equal(headers["x-api-key"], undefined);
    assert.equal(headers["x-harmless"], "retained");
  });

  test(`${name} Axios retains text, JSON, redirects and binary range requests`, async (t) => {
    const base = await listen(t, (request, response) => {
      if (request.url === "/redirect") {
        response.writeHead(302, { Location: "/json" });
        response.end();
      } else if (request.url === "/json") {
        response.setHeader("Content-Type", "application/json");
        response.end('{"items":[{"id":"1"}]}');
      } else if (request.url === "/text") {
        response.end("<html>content</html>");
      } else {
        assert.equal(request.headers.range, "bytes=2-5");
        response.writeHead(206, { "Content-Range": "bytes 2-5/10" });
        response.end(Buffer.from([2, 3, 4, 5]));
      }
    });
    const client = load("axios").create({
      proxy: false,
      timeout: 3000,
      maxContentLength: 16 * 1024 * 1024,
      maxBodyLength: 1024 * 1024,
    });
    assert.deepEqual((await client.get(base + "/redirect")).data, {
      items: [{ id: "1" }],
    });
    assert.equal(
      (await client.get(base + "/text", { responseType: "text" })).data,
      "<html>content</html>"
    );
    const range = await client.get(base + "/media", {
      headers: { Range: "bytes=2-5" },
      responseType: "arraybuffer",
    });
    assert.equal(range.status, 206);
    assert.deepEqual(Buffer.from(range.data), Buffer.from([2, 3, 4, 5]));
  });
}
