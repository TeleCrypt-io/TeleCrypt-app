config.resolve.fallback = {
    crypto: false,
    fs: false,
    path: false,
    stream: false,
    buffer: false,
    os: false,
    url: false,
};
config.resolve.preferRelative = true;

const CopyPlugin = require("copy-webpack-plugin");
config.plugins.push(
        new CopyPlugin({
            patterns: [
                {from: "../../node_modules/@matrix-org/olm/olm.wasm", to: "."},
            ],
        })
)

config.devServer.historyApiFallback = {
    disableDotRule: true,
    rewrites: [
        { from: /\/sso\.html(\/.*)/, to: ({ request, parsedUrl, match }) => match[1] },
        { from: /\/sso\.html/, to: '/index.html' },
    ],
}
config.devServer.headers = {
  "Access-Control-Allow-Origin" : "*",
  "Access-Control-Allow-Methods": "GET",
  "Access-Control-Allow-Headers": "content-type"
}
