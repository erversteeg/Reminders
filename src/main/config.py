line1  = '    @ConfigSection('
line2  = '            name = "{ucName}",'
line3  = '            description = "Reminder {number}",'
line4  = '            position = {pos},'
line5  = '            closedByDefault = true'
line6  = '    )'
line7  = '    String {lcName}= "{lcName}";'
line8  = ''
line9  = '    @ConfigItem('
line10 = '            keyName = "enable{ucName}",'
line11 = '            position = 0,'
line12 = '            name = "Enable",'
line13 = '            description = "Configures whether or not reminder is enabled.",'
line14 = '            section = {lcName}'
line15 = '    )'
line16 = '    default boolean {lcName}Enable()  {{ return false; }}'
line17 = ''
line18 = '    @ConfigItem('
line19 = '            keyName = "{lcName}Text",'
line20 = '            position = 1,'
line21 = '            name = "Text",'
line22 = '            description = "Configures the text.",'
line23 = '            section = {lcName}'
line24 = '    )'
line25 = '    default String {lcName}Text() {{ return ""; }}'
line26 = ''
line27 = '    @ConfigItem('
line28 = '            keyName = "{lcName}Color",'
line29 = '            position = 2,'
line30 = '            name = "Color",'
line31 = '            description = "Configures the color.",'
line32 = '            section = {lcName}'
line33 = '    )'
line34 = '    default Color {lcName}Color() {{ return Color.WHITE; }}'
line35 = ''
line36 = '    @ConfigItem('
line37 = '            keyName = "{lcName}Times",'
line38 = '            position = 37,'
line39 = '            name = "Times of Day",'
line40 = '            description = "Configures what times reminder is shown. Set times 10:00pm, 7:30pm or spans 8:30am-12:00pm, 10:00pm-1:00am. (comma separated).",'
line41 = '            section = {lcName}'
line42 = '    )'
line43 = '    default String {lcName}Times() {{ return ""; }}'
line44 = ''
line45 = '    @ConfigItem('
line46 = '            keyName = "{lcName}DaysOfWeek",'
line47 = '            position = 38,'
line48 = '            name = "Days of Week",'
line49 = '            description = "Configures what days of week reminder is shown, e.g. Mon, Thu (comma separated).",'
line50 = '            section = {lcName}'
line51 = '    )'
line52 = '    default String {lcName}DaysOfWeek() {{ return ""; }}'
line53 = ''
line108 = '     @ConfigItem('
line109 = '           keyName = "{lcName}Datess",'
line110= '            position = 39,'
line111 = '           name = "Calendar Dates",'
line112 = '           description = "Configures what dates reminder is shown. Set dates 04/30, 05/28/23 or ranges 9/20/22-10/30/22, 7/20-8/9. (comma separated).",'
line113 = '           section = {lcName}'
line114 = '      )'
line115 = '      default String {lcName}Dates() {{ return ""; }}'
line116 = ''
line117 = '      @ConfigItem('
line118 = '            keyName = "{lcName}Coordinates",'
line119 = '            position = 30,'
line120 = '            name = "Near Coordinates",'
line121 = '            description = "Configures coordinates where reminder is shown (x, y), e.g. (40, 50), (1000, 750) (comma separated).",'
line122 = '            section = {lcName}'
line123 = '      )'
line124 = '      default String {lcName}Coordinates() {{ return ""; }}'
line125 = ''
line126 = '      @ConfigItem('
line127 = '            keyName = "{lcName}Geofences",'
line128 = '            position = 31,'
line129 = '            name = "Inside Geofences",'
line130 = '            description = "Configures geofences where reminder is shown (west, north, east, south), e.g. (40, 50, 1000, 750) (comma separated).",'
line131 = '            section = {lcName}'
line132 = '      )'
line133 = '      default String {lcName}Geofences() {{ return ""; }}'
line134 = ''
line135 = '      @ConfigItem('
line136 = '            keyName = "{lcName}Radius",'
line137 = '            position = 32,'
line138 = '            name = "Coordinate Radius",'
line139 = '            description = "Configures how far away from coordinates reminder is shown.",'
line140 = '            section = {lcName}'
line141 = '      )'
line142 = '      default int {lcName}Radius() {{ return 0; }}'
line143 = ''
line144 = '      @ConfigItem('
line145 = '      keyName = "{ucName}RegionId",'
line146 = '            position = 33,'
line147 = '            name = "Inside Regions",'
line148 = '            description = "Configures region ids when reminder is shown (comma separated).",'
line149 = '            section = {lcName}'
line150 = '      )'
line151 = '      default String {lcName}RegionIds() {{ return ""; }}'
line152 = ''
line153 = '      @ConfigItem('
line154 = '      keyName = "{ucName}NpcIds",'
line155 = '            position = 34,'
line156 = '            name = "Npcs Spawned",'
line157 = '            description = "Configures the npc ids when spawned reminder is shown (comma separated).",'
line158 = '            section = {lcName}'
line159 = '      )'
line160 = '      default String {lcName}NpcIds() {{ return ""; }}'
line161 = ''
line162 = '      @ConfigItem('
line163 = '      keyName = "{ucName}ItemIds",'
line164 = '            position = 35,'
line165 = '            name = "Items in Inventory",'
line166 = '            description = "Configures the item ids in inventory for when reminder is shown (comma separated).",'
line167 = '            section = {lcName}'
line168 = '      )'
line169 = '      default String {lcName}ItemIds() {{ return ""; }}'
line170 = ''
line171 = '      @ConfigItem('
line172 = '            keyName = "{ucName}Duration",'
line173 = '            position = 3,'
line174 = '            name = "Duration",'
line175 = '            description = "Configures the duration for reminder (Duration 0, Cooldown 0 = infinite).",'
line176 = '            section = {lcName}'
line177 = '      )'
line178 = '      default int {lcName}Duration() {{ return 0; }}'
line179 = ''
line180 = '      @ConfigItem('
line181 = '            keyName = "{ucName}Cooldown",'
line182 = '            position = 4,'
line183 = '            name = "Cooldown",'
line184 = '            description = "Configures how long before reminder can be shown again.",'
line185 = '            section = {lcName}'
line186 = '      )'
line187 = '      default int {lcName}Cooldown() {{ return 0; }}'
line188 = ''
line189 = '      @ConfigItem('
line190 = '            keyName = "{ucName}TimeUnit",'
line191 = '            position = 5,'
line192 = '            name = "Time Unit",'
line193 = '            description = "Configures the time unit for duration and cooldown.",'
line194 = '            section = {lcName}'
line195 = '      )'
line196 = '      default TimeUnit {lcName}TimeUnit() {{ return TimeUnit.SECONDS; }}'
line197 = ''
line198 = '      @ConfigItem('
line199 = '            keyName = "{ucName}ChatPatterns",'
line200 = '            position = 36,'
line201 = '            name = "Chat Patterns",'
line202 = '            description = "Configures what text or regex patterns to match in chat messages to show reminder. (comma seperated)",'
line203 = '            section = {lcName}'
line204 = '      )'
line205 = '      default String {lcName}ChatPatterns() {{ return ""; }}'
line206 = ''
line207 = '      @ConfigItem('
line208 = '            keyName = "{ucName}Notification",'
line209 = '            position = 6,'
line210 = '            name = "Notification",'
line211 = '            description = "Configures whether or not to show a background notification.",'
line212 = '            section = {lcName}'
line213 = '      )'
line214 = '      default boolean {lcName}Notify() {{ return false; }}'
line215 = ''
line216 = '      @ConfigItem('
line217 = '            keyName = "{ucName}SeparatePanel",'
line218 = '            position = 7,'
line219 = '            name = "Separate Panel",'
line220 = '            description = "Configures whether or not reminder is shown in separate panel.",'
line221 = '            section = {lcName}'
line222 = '      )'
line223 = '      default boolean {lcName}SeparatePanel() {{ return false; }}'
line224 = ''
line225 = '      @ConfigItem('
line226 = '            keyName = "{ucName}panelAnchorType",'
line227 = '            position = 8,'
line228 = '            name = "Panel Anchor Type",'
line229 = '            description = "Configures the anchor type for the panel.",'
line230 = '            section = {lcName}'
line231 = '      )'
line232 = '      default RSAnchorType {lcName}PanelAnchorType() {{ return RSAnchorType.TOP_LEFT; }}'
line233 = ''
line234 = '      @ConfigItem('
line235 = '            keyName = "{ucName}PanelAnchorX",'
line236 = '            position = 9,'
line237 = '            name = "Panel Anchor X",'
line238 = '            description = "Configures the anchor x position for the panel.",'
line239 = '            section = {lcName}'
line240 = '      )'
line241 = '      default int {lcName}PanelAnchorX() {{ return 0; }}'
line242 = ''
line243 = '      @ConfigItem('
line244 = '            keyName = "{ucName}PanelAnchorY",'
line245 = '            position = 10,'
line246 = '            name = "Panel Anchor Y",'
line247 = '            description = "Configures the anchor y position for the panel.",'
line248 = '            section = {lcName}'
line249 = '      )'
line250 = '      default int {lcName}PanelAnchorY() {{ return 0; }}'
line251 = ''
line252 = '      @ConfigItem('
line253 = '            keyName = "{ucName}ImageAlignment",'
line254 = '            position = 11,'
line255 = '            name = "Image Alignment",'
line256 = '            description = "Configures the image alignment.",'
line257 = '            section = {lcName}'
line258 = '      )'
line259 = '      default RSViewGroup.Gravity {lcName}ImageAlignment() {{ return RSViewGroup.Gravity.TOP_START; }}'
line260 = ''
line261 = '      @ConfigItem('
line262 = '            keyName = "{ucName}ImageId",'
line263 = '            position = 12,'
line264 = '            name = "Image ID",'
line265 = '            description = "Configures the image id.",'
line266 = '            section = {lcName}'
line267 = '      )'
line268 = '      default int {lcName}ImageId() {{ return 0; }}'
line269 = ''
line270 = '      @ConfigItem('
line271 = '            keyName = "{ucName}Image",'
line272 = '            position = 13,'
line273 = '            name = "Image",'
line274 = '            description = "Configures whether or not the reminder has an image.",'
line275 = '            section = {lcName}'
line276 = '      )'
line277 = '      default boolean {lcName}Image() {{ return false; }}'
line278 = ''
line279 = '      @ConfigItem('
line280 = '            keyName = "{ucName}Animate",'
line281 = '            position = 14,'
line282 = '            name = "Animate",'
line283 = '            description = "Configures whether or not to animate.",'
line284 = '            section = {lcName}'
line285 = '      )'
line286 = '      default boolean {lcName}Animate() {{ return false; }}'
line287 = ''
line288 = '      @ConfigItem('
line289 = '            keyName = "{ucName}AnimationType",'
line290 = '            position = 15,'
line291 = '            name = "Animation Type",'
line292 = '            description = "Configures the animation type.",'
line293 = '            section = {lcName}'
line294 = '      )'
line295 = '      default ColorAnimationType {lcName}AnimationType() {{ return ColorAnimationType.ANALOGOUS; }}'
line296 = ''
line297 = '      @ConfigItem('
line298 = '            keyName = "{ucName}CycleDuration",'
line299 = '            position = 16,'
line300 = '            name = "Cycle Duration",'
line301 = '            description = "Configures the cycle duration for animation.",'
line302 = '            section = {lcName}'
line303 = '      )'
line304 = '      default int {lcName}CycleDuration() {{ return 2; }}'
line305 = ''


config = line1 + "\n" + line2 + "\n" + line3 + "\n" + line4 + "\n" + line5 + "\n" + line6 + "\n" + line7 + "\n" + line8 + \
         "\n" + line9 + "\n" + line10 + "\n" + line11 + "\n" + line12 + "\n" + line13 + "\n" + line14 + "\n" + line15 + "\n" + \
         line16 + "\n" + line17 + "\n" + line18 + "\n" + line19 + "\n" + line20 + "\n" + line21 + "\n" + line22 + "\n" + line23 + \
         "\n" + line24 + "\n" + line25 + "\n" + line26 + "\n" + line27 + "\n" + line28 + "\n" + line29 + "\n" + line30 + "\n" + line31 + \
         "\n" + line32 + "\n" + line33 + "\n" + line34 + "\n" + line35 + "\n" + line36 + "\n" + line37 + "\n" + line38 + "\n" + line39 + \
         "\n" + line40 + "\n" + line41 + "\n" + line42 + "\n" + line43 + "\n" + line44 + "\n" + line45 + "\n" + line46 + "\n" + line47 + \
         "\n" + line48 + "\n" + line49 + "\n" + line50 + "\n" + line51 + "\n" + line52 + "\n" + line53 + "\n" + line108 + "\n" + line109 + \
         "\n" + line110 + "\n" + line111 + "\n" + line112 + "\n" + line113 + "\n" + line114 + "\n" + line115 + "\n" + line116 + \
         "\n" + line117 + "\n" + line118 + "\n" + line119 + "\n" + line120 + "\n" + line121 + "\n" + line122 + "\n" + line123 + \
         "\n" + line124 + "\n" + line125 + "\n" + line126 + "\n" + line127 + "\n" + line128 + "\n" + line129 + "\n" + line130 + \
         "\n" + line131 + "\n" + line132 + "\n" + line133 + "\n" + line134 + "\n" + line135 + "\n" + line136 + "\n" + line137 + \
         "\n" + line138 + "\n" + line139 + "\n" + line140 + "\n" + line141 + "\n" + line142 + "\n" + line143 + "\n" + line144 + \
         "\n" + line145 + "\n" + line146 + "\n" + line147 + "\n" + line148 + "\n" + line149 + "\n" + line150 + "\n" + line151 + \
         "\n" + line152 + "\n" + line153 + "\n" + line154 + "\n" + line155 + "\n" + line156 + "\n" + line157 + "\n" + line158 + \
         "\n" + line159 + "\n" + line160 + "\n" + line161 + "\n" + line162 + "\n" + line163 + "\n" + line164 + "\n" + line165 + \
         "\n" + line166 + "\n" + line167 + "\n" + line168 + "\n" + line169 + "\n" + line170 + \
         "\n" + line171 + "\n" + line172 + "\n" + line173 + "\n" + line174 + "\n" + line175 + \
         "\n" + line176 + "\n" + line177 + "\n" + line178 + "\n" + line179 + "\n" + line180 + \
         "\n" + line181 + "\n" + line182 + "\n" + line183 + "\n" + line184 + "\n" + line185 + \
         "\n" + line186 + "\n" + line187 + "\n" + line188 + "\n" + line189 + "\n" + line190 + \
         "\n" + line191 + "\n" + line192 + "\n" + line193 + "\n" + line194 + "\n" + line195 + \
         "\n" + line196 + "\n" + line197 + "\n" + line198 + "\n" + line199 + "\n" + line200 + \
         "\n" + line201 + "\n" + line202 + "\n" + line203 + "\n" + line204 + "\n" + line205 + \
         "\n" + line206 + "\n" + line207 + "\n" + line208 + "\n" + line209 + "\n" + line210 + \
         "\n" + line211 + "\n" + line212 + "\n" + line213 + "\n" + line214 + "\n" + line215 + \
         "\n" + line216 + "\n" + line217 + "\n" + line218 + "\n" + line219 + "\n" + line220 + \
         "\n" + line221 + "\n" + line222 + "\n" + line223 + "\n" + line224 + "\n" + line225 + \
         "\n" + line226 + "\n" + line227 + "\n" + line228 + "\n" + line229 + "\n" + line230 + \
         "\n" + line231 + "\n" + line232 + "\n" + line233 + "\n" + line234 + "\n" + line235 + \
         "\n" + line236 + "\n" + line237 + "\n" + line238 + "\n" + line239 + "\n" + line240 + \
         "\n" + line241 + "\n" + line242 + "\n" + line243 + "\n" + line244 + "\n" + line245 + \
         "\n" + line246 + "\n" + line247 + "\n" + line248 + "\n" + line249 + "\n" + line250 + \
         "\n" + line251 + "\n" + line252 + "\n" + line253 + "\n" + line254 + "\n" + line255 + \
         "\n" + line256 + "\n" + line257 + "\n" + line258 + "\n" + line259 + "\n" + line260 + \
         "\n" + line261 + "\n" + line262 + "\n" + line263 + "\n" + line264 + "\n" + line265 + \
         "\n" + line266 + "\n" + line267 + "\n" + line268 + "\n" + line269 + "\n" + line270 + \
         "\n" + line271 + "\n" + line272 + "\n" + line273 + "\n" + line274 + "\n" + line275 + \
         "\n" + line276 + "\n" + line277 + "\n" + line278 + "\n" + line279 + "\n" + line280 + \
         "\n" + line281 + "\n" + line282 + "\n" + line283 + "\n" + line284 + "\n" + line285 + \
         "\n" + line286 + "\n" + line287 + "\n" + line288 + "\n" + line289 + "\n" + line290 + \
         "\n" + line291 + "\n" + line292 + "\n" + line293 + "\n" + line294 + "\n" + line295 + \
         "\n" + line296 + "\n" + line297 + "\n" + line298 + "\n" + line299 + "\n" + line300 + \
         "\n" + line301 + "\n" + line302 + "\n" + line303 + "\n" + line304 + "\n" + line305

# string = ""
# for i in range(296, 306):
#     string += "\"\\n\" + line" + str(i) + " + "
#     if i % 5 == 0:
#         string += "\\\n"
#
# print(string)

for index in range(1,101):
    print(config.format(number=index, ucName="Reminder" + str(index), lcName="reminder" + str(index), pos=(20 + index)))