module.exports = {
  apps: [{
    name: 'panel',
    script: 'server.js',
    interpreter: 'node',
    interpreter_args: '--experimental-vm-modules',
    cwd: '/opt/panel/remote-control',
    env: {
      NODE_ENV: 'production',
      PORT: '80'
    },
    watch: false,
    autorestart: true,
    max_restarts: 10,
    restart_delay: 2000
  }]
};
