var exec = require('cordova/exec');

exports.verify = function (options, success, error) {
    options = options || {};
    exec(success, error, 'TurnstilePlugin', 'verify', [options]);
};

exports.cancel = function (success, error) {
    exec(success, error, 'TurnstilePlugin', 'cancel', []);
};

exports.showBadge = function (options, success, error) {
    options = options || {};
    exec(success, error, 'TurnstilePlugin', 'showBadge', [options]);
};

exports.hideBadge = function (success, error) {
    exec(success, error, 'TurnstilePlugin', 'hideBadge', []);
};
