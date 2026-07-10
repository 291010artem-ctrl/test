module.exports = {
  apps: [{
    name: 'panel',
    script: 'server.js',
    cwd: '/opt/panel/remote-control',
    env: {
      PORT: '80',
      AUTO_START: '1'
    }
  }]
};
