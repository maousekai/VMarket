const { AndroidConfig, withAndroidStyles } = require('expo/config-plugins')

module.exports = function withLightAndroidTheme(config) {
  return withAndroidStyles(config, (config) => {
    const appTheme = config.modResults.resources.style.find((style) => style.$.name === 'AppTheme')
    appTheme.$.parent = 'Theme.AppCompat.Light.NoActionBar'
    config.modResults = AndroidConfig.Styles.assignStylesValue(config.modResults, {
      add: true,
      parent: { name: 'AppTheme' },
      name: 'android:forceDarkAllowed',
      value: 'false',
    })
    return config
  })
}
