config.resolve.fallback = {
    crypto: false,
    fs: false,
    path: false,
    stream: false,
    buffer: false,
    os: false,
    url: false,
};

// Minification
if (config.mode === "production") {
    const TerserPlugin = require("terser-webpack-plugin");
    config.optimization = {
        minimize: true,
        minimizer: [
            new TerserPlugin({
                extractComments: true,
                terserOptions: {
                    compress: {
                        collapse_vars: false, // > 5 min
                        reduce_vars: false, // 30 secs
                    },
                    mangle: true,
                },
            }),
        ],
    };
} else {
    config.optimization = {
        minimize: false,
        minimizer: [],
    };
}


const CopyPlugin = require("copy-webpack-plugin");
config.plugins.push(
    new CopyPlugin({
        patterns: [
            {from: "../../node_modules/@matrix-org/olm/olm.wasm", to: "."},
            {from: "../../node_modules/pdfjs-dist/build/pdf.worker.mjs", to: "."},
        ],
    })
)

if (config.devServer) {
    config.devServer.historyApiFallback = {
        disableDotRule: true,
        rewrites: [
            {from: /\/sso\.html(\/.*)/, to: ({request, parsedUrl, match}) => match[1]},
            {from: /\/sso\.html/, to: '/index.html'},
        ],
    }
    config.devServer.headers = {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "GET",
        "Access-Control-Allow-Headers": "content-type"
    }
}
