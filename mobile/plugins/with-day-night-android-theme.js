const { AndroidConfig, withAndroidStyles } = require('expo/config-plugins')

/**
 * App tu ve theme sang / toi (src/theme). Theme Android theo DayNight de thanh he thong dung che do
 * cua may, va tat forceDark de ROM (Flyme, MIUI...) khong dao mau them lan nua len theme toi cua
 * app (review PR 68 lan 4, muc 1.3).
 */
module.exports = function withDayNightAndroidTheme(config) {
  return withAndroidStyles(config, (config) => {
    const appTheme = config.modResults.resources.style.find((style) => style.$.name === 'AppTheme')
    appTheme.$.parent = 'Theme.AppCompat.DayNight.NoActionBar'
    config.modResults = AndroidConfig.Styles.assignStylesValue(config.modResults, {
      add: true,
      parent: { name: 'AppTheme' },
      name: 'android:forceDarkAllowed',
      value: 'false',
    })
    return config
  })
}
