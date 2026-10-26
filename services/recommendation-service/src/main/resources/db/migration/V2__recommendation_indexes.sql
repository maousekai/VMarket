CREATE INDEX item_feature_category_path_idx ON item_feature USING GIN ((snapshot->'categoryPath'));
CREATE INDEX item_feature_popularity_idx ON item_feature
    (((snapshot->>'soldCount')::bigint) DESC, ((snapshot->>'ratingAverage')::numeric) DESC, product_id)
    WHERE snapshot->>'catalogVisible' = 'true' AND snapshot->>'deleted' = 'false'
          AND (snapshot->>'availableStock')::bigint > 0;
CREATE INDEX processed_event_processed_at_idx ON processed_event (processed_at);
