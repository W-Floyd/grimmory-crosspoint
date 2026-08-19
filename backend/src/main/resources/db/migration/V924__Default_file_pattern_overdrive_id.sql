-- Add the OverDrive edition id to the default file naming pattern.
--
-- getOrCreateSetting only writes a default when the row is absent, so changing the constant in code
-- affects fresh installs alone; an existing install keeps whatever was stored on first boot. This
-- updates that stored value.
--
-- Guarded on the exact previous default: a pattern that has been customised is left alone, because
-- overwriting a deliberate choice would rename someone's whole library on the next Move & Organize.
--
-- The appended block is optional (<...>), so it contributes nothing for books with no OverDrive id —
-- no dangling "[od-]" on anything added by upload, Bookdrop or a scan.
UPDATE app_settings
   SET val = '{authors}/<{series}/><{seriesIndex} - >{title}/<{narrator}/>{title}< - {authors}>< ({year})>< [od-{overdriveId}]>'
 WHERE name = 'upload_file_pattern'
   AND val = '{authors}/<{series}/><{seriesIndex} - >{title}/<{narrator}/>{title}< - {authors}>< ({year})>';
